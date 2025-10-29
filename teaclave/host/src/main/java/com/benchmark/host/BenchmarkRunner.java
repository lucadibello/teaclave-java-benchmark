package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class BenchmarkRunner {

    private static final double NANOS_IN_MILLI = 1_000_000.0;

    private final Service service;
    private final Random random;

    BenchmarkRunner(Service service) {
        this(service, new Random(0L));
    }

    BenchmarkRunner(Service service, Random random) {
        this.service = service;
        this.random = random;
    }

    CalibratedWorkload calibrate(CalibrationSettings settings) {
        int size = settings.getInitialSize();
        int attempts = 0;
        double averageMillis = 0.0;
        double[] dataset = createDataset(size);

        while (size <= settings.getMaxSize()) {
            averageMillis = measureAverageMillis(dataset, settings.getSigma(), settings.getWarmupIterations(),
                    settings.getMeasureIterations());
            attempts++;
            if (averageMillis >= settings.getTargetMillis()) {
                break;
            }
            int nextSize = Math.max(size + 1, Math.min(settings.getMaxSize(), size * settings.getGrowthFactor()));
            if (nextSize == size) {
                break;
            }
            size = nextSize;
            dataset = createDataset(size);
        }

        return new CalibratedWorkload(size, settings.getSigma(), settings.getMeasureIterations(),
                averageMillis, attempts);
    }

    List<WeakScalingResult> runWeakScaling(CalibratedWorkload workload, int[] scaleFactors, int iterations) {
        int[] factors = Arrays.copyOf(scaleFactors, scaleFactors.length);
        Arrays.sort(factors);
        List<WeakScalingResult> results = new ArrayList<>(factors.length);
        for (int scale : factors) {
            int dataSize = Math.max(1, workload.getDataSize() * scale);
            double[] dataset = createDataset(dataSize);
            double averageMillis = measureAverageMillis(dataset, workload.getSigma(), 1, iterations);
            results.add(new WeakScalingResult(scale, dataSize, iterations, averageMillis));
        }
        return results;
    }

    List<StrongScalingResult> runStrongScaling(CalibratedWorkload workload, int[] partitionCounts, int iterations) {
        int[] partitions = Arrays.copyOf(partitionCounts, partitionCounts.length);
        Arrays.sort(partitions);
        int maxPartitions = partitions[partitions.length - 1];
        int totalSize = Math.max(maxPartitions, workload.getDataSize() * maxPartitions);
        double[] fullDataset = createDataset(totalSize);

        List<StrongScalingResult> results = new ArrayList<>(partitions.length);
        for (int partitionCount : partitions) {
            double[][] slices = splitDataset(fullDataset, partitionCount);
            double averageMillis = measureStrongAverageMillis(slices, workload.getSigma(), iterations);
            int minPartitionSize = Integer.MAX_VALUE;
            int maxPartitionSize = Integer.MIN_VALUE;
            for (double[] slice : slices) {
                minPartitionSize = Math.min(minPartitionSize, slice.length);
                maxPartitionSize = Math.max(maxPartitionSize, slice.length);
            }
            results.add(new StrongScalingResult(partitionCount, totalSize, minPartitionSize,
                    maxPartitionSize, iterations, averageMillis));
        }
        return results;
    }

    private double measureAverageMillis(double[] dataset, double sigma, int warmupIterations, int measureIterations) {
        if (warmupIterations > 0) {
            double[] warmupCopy = Arrays.copyOf(dataset, dataset.length);
            executeIterations(warmupCopy, sigma, warmupIterations);
        }
        double[] measureCopy = Arrays.copyOf(dataset, dataset.length);
        return executeIterations(measureCopy, sigma, measureIterations);
    }

    private double measureStrongAverageMillis(double[][] slices, double sigma, int measureIterations) {
        for (int i = 0; i < 1; i++) {
            executeStrongIteration(slices, sigma);
        }
        long start = System.nanoTime();
        double lastResult = 0.0;
        for (int i = 0; i < measureIterations; i++) {
            lastResult = executeStrongIteration(slices, sigma);
        }
        long duration = System.nanoTime() - start;
        return duration / (measureIterations * NANOS_IN_MILLI);
    }

    private double executeIterations(double[] dataset, double sigma, int iterations) {
        long start = System.nanoTime();
        double lastResult = 0.0;
        for (int i = 0; i < iterations; i++) {
            service.initBinaryAggregation(dataset.length, sigma);
            for (double value : dataset) {
                lastResult = service.addToBinaryAggregation(value);
            }
            lastResult = service.getBinaryAggregationSum();
            perturbDataset(dataset);
        }
        long duration = System.nanoTime() - start;
        double averageMillis = duration / (iterations * NANOS_IN_MILLI);
        if (Double.isNaN(lastResult)) {
            throw new IllegalStateException("Computation produced NaN");
        }
        return averageMillis;
    }

    private double executeStrongIteration(double[][] slices, double sigma) {
        double last = 0.0;
        for (double[] slice : slices) {
            service.initBinaryAggregation(slice.length, sigma);
            for (double value : slice) {
                last = service.addToBinaryAggregation(value);
            }
            last = service.getBinaryAggregationSum();
            perturbDataset(slice);
        }
        if (Double.isNaN(last)) {
            throw new IllegalStateException("Strong scaling produced NaN");
        }
        return last;
    }

    private double[] createDataset(int size) {
        double[] data = new double[size];
        for (int i = 0; i < size; i++) {
            data[i] = i + 1;
        }
        return data;
    }

    private void perturbDataset(double[] dataset) {
        for (int i = 0; i < dataset.length; i++) {
            dataset[i] += random.nextGaussian() * 0.001;
        }
    }

    private double[][] splitDataset(double[] source, int partitions) {
        if (partitions <= 0) {
            throw new IllegalArgumentException("Part count must be positive");
        }
        double[][] result = new double[partitions][];
        int baseSize = source.length / partitions;
        int remainder = source.length % partitions;
        int offset = 0;
        for (int i = 0; i < partitions; i++) {
            int currentSize = baseSize + (i < remainder ? 1 : 0);
            double[] part = new double[currentSize];
            System.arraycopy(source, offset, part, 0, currentSize);
            result[i] = part;
            offset += currentSize;
        }
        return result;
    }

    static final class CalibrationSettings {
        private static final String ENV_INITIAL_SIZE = "TEACLAVE_BENCH_INITIAL_SIZE";
        private static final String ENV_MAX_SIZE = "TEACLAVE_BENCH_MAX_SIZE";
        private static final String ENV_TARGET_MILLIS = "TEACLAVE_BENCH_TARGET_MS";
        private static final String ENV_TARGET_MICROS = "TEACLAVE_BENCH_TARGET_US";
        private static final String ENV_GROWTH_FACTOR = "TEACLAVE_BENCH_GROWTH_FACTOR";
        private static final String ENV_WARMUP = "TEACLAVE_BENCH_WARMUP";
        private static final String ENV_MEASURE = "TEACLAVE_BENCH_MEASURE";

        private final int initialSize;
        private final int maxSize;
        private final double targetMillis;
        private final int growthFactor;
        private final int warmupIterations;
        private final int measureIterations;
        private final double sigma;

        CalibrationSettings(int initialSize,
                            int maxSize,
                            double targetMillis,
                            int growthFactor,
                            int warmupIterations,
                            int measureIterations,
                            double sigma) {
            this.initialSize = initialSize;
            this.maxSize = maxSize;
            this.targetMillis = targetMillis;
            this.growthFactor = growthFactor;
            this.warmupIterations = warmupIterations;
            this.measureIterations = measureIterations;
            this.sigma = sigma;
        }

        static CalibrationSettings fromEnvironment(double sigma) {
            int initialSize = parseIntEnv(ENV_INITIAL_SIZE, 256);
            int maxSize = parseIntEnv(ENV_MAX_SIZE, 1 << 16);
            Double targetMillisEnv = parseOptionalDoubleEnv(ENV_TARGET_MILLIS);
            double targetMillis;
            if (targetMillisEnv != null) {
                targetMillis = targetMillisEnv;
            } else {
                double targetMicros = parseDoubleEnv(ENV_TARGET_MICROS, 500.0);
                targetMillis = targetMicros / 1_000.0;
            }
            int growthFactor = parseIntEnv(ENV_GROWTH_FACTOR, 2);
            int warmupIterations = parseIntEnv(ENV_WARMUP, 3);
            int measureIterations = parseIntEnv(ENV_MEASURE, 5);
            return new CalibrationSettings(initialSize, maxSize, targetMillis, growthFactor,
                    warmupIterations, measureIterations, sigma);
        }

        int getInitialSize() {
            return initialSize;
        }

        int getMaxSize() {
            return maxSize;
        }

        double getTargetMillis() {
            return targetMillis;
        }

        int getGrowthFactor() {
            return growthFactor;
        }

        int getWarmupIterations() {
            return warmupIterations;
        }

        int getMeasureIterations() {
            return measureIterations;
        }

        double getSigma() {
            return sigma;
        }

        private static int parseIntEnv(String key, int defaultValue) {
            String raw = System.getenv(key);
            if (raw == null || raw.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Unable to parse integer from " + key + "=" + raw, nfe);
            }
        }

        private static double parseDoubleEnv(String key, double defaultValue) {
            String raw = System.getenv(key);
            if (raw == null || raw.isEmpty()) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Unable to parse double from " + key + "=" + raw, nfe);
            }
        }

        private static Double parseOptionalDoubleEnv(String key) {
            String raw = System.getenv(key);
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Unable to parse double from " + key + "=" + raw, nfe);
            }
        }
    }

    static final class CalibratedWorkload {
        private final int dataSize;
        private final double sigma;
        private final int iterations;
        private final double averageMillis;
        private final int attempts;

        CalibratedWorkload(int dataSize, double sigma, int iterations,
                           double averageMillis, int attempts) {
            this.dataSize = dataSize;
            this.sigma = sigma;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
            this.attempts = attempts;
        }

        int getDataSize() {
            return dataSize;
        }

        double getSigma() {
            return sigma;
        }

        int getIterations() {
            return iterations;
        }

        double getAverageMillis() {
            return averageMillis;
        }

        int getAttempts() {
            return attempts;
        }

        @Override
        public String toString() {
            return "CalibratedWorkload{" +
                    "dataSize=" + dataSize +
                    ", sigma=" + sigma +
                    ", iterations=" + iterations +
                    ", avgTimeMillis=" + String.format("%.3f", averageMillis) +
                    ", attempts=" + attempts +
                    '}';
        }
    }

    static final class WeakScalingResult {
        private final int scaleFactor;
        private final int dataSize;
        private final int iterations;
        private final double averageMillis;

        WeakScalingResult(int scaleFactor, int dataSize, int iterations, double averageMillis) {
            this.scaleFactor = scaleFactor;
            this.dataSize = dataSize;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
        }

        int getScaleFactor() {
            return scaleFactor;
        }

        int getDataSize() {
            return dataSize;
        }

        int getIterations() {
            return iterations;
        }

        double getAverageMillis() {
            return averageMillis;
        }
    }

    static final class StrongScalingResult {
        private final int partitionCount;
        private final int totalSize;
        private final int minPartitionSize;
        private final int maxPartitionSize;
        private final int iterations;
        private final double averageMillis;

        StrongScalingResult(int partitionCount, int totalSize, int minPartitionSize,
                            int maxPartitionSize, int iterations, double averageMillis) {
            this.partitionCount = partitionCount;
            this.totalSize = totalSize;
            this.minPartitionSize = minPartitionSize;
            this.maxPartitionSize = maxPartitionSize;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
        }

        int getPartitionCount() {
            return partitionCount;
        }

        int getTotalSize() {
            return totalSize;
        }

        int getMinPartitionSize() {
            return minPartitionSize;
        }

        int getMaxPartitionSize() {
            return maxPartitionSize;
        }

        int getIterations() {
            return iterations;
        }

        double getAverageMillis() {
            return averageMillis;
        }
    }

    static final class BenchmarkSummary {
        private final CalibratedWorkload calibration;
        private final List<WeakScalingResult> weakScalingResults;
        private final List<StrongScalingResult> strongScalingResults;

        BenchmarkSummary(CalibratedWorkload calibration,
                         List<WeakScalingResult> weakScalingResults,
                         List<StrongScalingResult> strongScalingResults) {
            this.calibration = calibration;
            this.weakScalingResults = Collections.unmodifiableList(new ArrayList<>(weakScalingResults));
            this.strongScalingResults = Collections.unmodifiableList(new ArrayList<>(strongScalingResults));
        }

        CalibratedWorkload getCalibration() {
            return calibration;
        }

        List<WeakScalingResult> getWeakScalingResults() {
            return weakScalingResults;
        }

        List<StrongScalingResult> getStrongScalingResults() {
            return strongScalingResults;
        }

        String toPrettyString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"calibration\": {\n");
            sb.append(String.format(Locale.US,
                    "    \"dataSize\": %d,%n    \"sigma\": %.6f,%n    \"iterations\": %d,%n    \"avgTimeMillis\": %.3f,%n    \"attempts\": %d%n",
                    calibration.getDataSize(), calibration.getSigma(), calibration.getIterations(),
                    calibration.getAverageMillis(), calibration.getAttempts()));
            sb.append("  },\n");
            sb.append("  \"weakScaling\": [\n");
            for (int i = 0; i < weakScalingResults.size(); i++) {
                WeakScalingResult result = weakScalingResults.get(i);
                sb.append(String.format(Locale.US,
                        "    {\"scaleFactor\": %d, \"dataSize\": %d, \"iterations\": %d, \"avgTimeMillis\": %.3f}",
                        result.getScaleFactor(), result.getDataSize(), result.getIterations(),
                        result.getAverageMillis()));
                if (i < weakScalingResults.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ],\n");
            sb.append("  \"strongScaling\": [\n");
            for (int i = 0; i < strongScalingResults.size(); i++) {
                StrongScalingResult result = strongScalingResults.get(i);
                sb.append(String.format(Locale.US,
                        "    {\"partitions\": %d, \"totalSize\": %d, \"minPartitionSize\": %d, \"maxPartitionSize\": %d, \"iterations\": %d, \"avgTimeMillis\": %.3f}",
                        result.getPartitionCount(), result.getTotalSize(), result.getMinPartitionSize(),
                        result.getMaxPartitionSize(), result.getIterations(), result.getAverageMillis()));
                if (i < strongScalingResults.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}");
            return sb.toString();
        }
    }
}
