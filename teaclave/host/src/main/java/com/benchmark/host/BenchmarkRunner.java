package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class BenchmarkRunner {

    private static final double NANOS_IN_MICRO = 1_000.0;

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
        double averageMicros = 0.0;
        double[] dataset = createDataset(size);

        while (size <= settings.getMaxSize()) {
            averageMicros = measureAverageMicros(dataset, settings.getSigma(), settings.getWarmupIterations(),
                    settings.getMeasureIterations());
            attempts++;
            if (averageMicros >= settings.getTargetMicros()) {
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
                averageMicros, attempts);
    }

    List<WeakScalingResult> runWeakScaling(CalibratedWorkload workload, int[] scaleFactors, int iterations) {
        int[] factors = Arrays.copyOf(scaleFactors, scaleFactors.length);
        Arrays.sort(factors);
        List<WeakScalingResult> results = new ArrayList<>(factors.length);
        for (int scale : factors) {
            int dataSize = Math.max(1, workload.getDataSize() * scale);
            double[] dataset = createDataset(dataSize);
            double averageMicros = measureAverageMicros(dataset, workload.getSigma(), 1, iterations);
            results.add(new WeakScalingResult(scale, dataSize, iterations, averageMicros));
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
            double averageMicros = measureStrongAverageMicros(slices, workload.getSigma(), iterations);
            int minPartitionSize = Integer.MAX_VALUE;
            int maxPartitionSize = Integer.MIN_VALUE;
            for (double[] slice : slices) {
                minPartitionSize = Math.min(minPartitionSize, slice.length);
                maxPartitionSize = Math.max(maxPartitionSize, slice.length);
            }
            results.add(new StrongScalingResult(partitionCount, totalSize, minPartitionSize,
                    maxPartitionSize, iterations, averageMicros));
        }
        return results;
    }

    private double measureAverageMicros(double[] dataset, double sigma, int warmupIterations, int measureIterations) {
        if (warmupIterations > 0) {
            double[] warmupCopy = Arrays.copyOf(dataset, dataset.length);
            executeIterations(warmupCopy, sigma, warmupIterations);
        }
        double[] measureCopy = Arrays.copyOf(dataset, dataset.length);
        return executeIterations(measureCopy, sigma, measureIterations);
    }

    private double measureStrongAverageMicros(double[][] slices, double sigma, int measureIterations) {
        for (int i = 0; i < 1; i++) {
            executeStrongIteration(slices, sigma);
        }
        long start = System.nanoTime();
        double lastResult = 0.0;
        for (int i = 0; i < measureIterations; i++) {
            lastResult = executeStrongIteration(slices, sigma);
        }
        long duration = System.nanoTime() - start;
        return duration / (measureIterations * NANOS_IN_MICRO);
    }

    private double executeIterations(double[] dataset, double sigma, int iterations) {
        long start = System.nanoTime();
        double lastResult = 0.0;
        for (int i = 0; i < iterations; i++) {
            lastResult = service.runBinaryAggregation(dataset, sigma);
            perturbDataset(dataset);
        }
        long duration = System.nanoTime() - start;
        double averageMicros = duration / (iterations * NANOS_IN_MICRO);
        if (Double.isNaN(lastResult)) {
            throw new IllegalStateException("Computation produced NaN");
        }
        return averageMicros;
    }

    private double executeStrongIteration(double[][] slices, double sigma) {
        double last = 0.0;
        for (double[] slice : slices) {
            last = service.runBinaryAggregation(slice, sigma);
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
        private static final String ENV_TARGET_MICROS = "TEACLAVE_BENCH_TARGET_US";
        private static final String ENV_GROWTH_FACTOR = "TEACLAVE_BENCH_GROWTH_FACTOR";
        private static final String ENV_WARMUP = "TEACLAVE_BENCH_WARMUP";
        private static final String ENV_MEASURE = "TEACLAVE_BENCH_MEASURE";

        private final int initialSize;
        private final int maxSize;
        private final double targetMicros;
        private final int growthFactor;
        private final int warmupIterations;
        private final int measureIterations;
        private final double sigma;

        CalibrationSettings(int initialSize,
                            int maxSize,
                            double targetMicros,
                            int growthFactor,
                            int warmupIterations,
                            int measureIterations,
                            double sigma) {
            this.initialSize = initialSize;
            this.maxSize = maxSize;
            this.targetMicros = targetMicros;
            this.growthFactor = growthFactor;
            this.warmupIterations = warmupIterations;
            this.measureIterations = measureIterations;
            this.sigma = sigma;
        }

        static CalibrationSettings fromEnvironment(double sigma) {
            int initialSize = parseIntEnv(ENV_INITIAL_SIZE, 256);
            int maxSize = parseIntEnv(ENV_MAX_SIZE, 1 << 16);
            double targetMicros = parseDoubleEnv(ENV_TARGET_MICROS, 500.0);
            int growthFactor = parseIntEnv(ENV_GROWTH_FACTOR, 2);
            int warmupIterations = parseIntEnv(ENV_WARMUP, 3);
            int measureIterations = parseIntEnv(ENV_MEASURE, 5);
            return new CalibrationSettings(initialSize, maxSize, targetMicros, growthFactor,
                    warmupIterations, measureIterations, sigma);
        }

        int getInitialSize() {
            return initialSize;
        }

        int getMaxSize() {
            return maxSize;
        }

        double getTargetMicros() {
            return targetMicros;
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
    }

    static final class CalibratedWorkload {
        private final int dataSize;
        private final double sigma;
        private final int iterations;
        private final double averageMicros;
        private final int attempts;

        CalibratedWorkload(int dataSize, double sigma, int iterations,
                           double averageMicros, int attempts) {
            this.dataSize = dataSize;
            this.sigma = sigma;
            this.iterations = iterations;
            this.averageMicros = averageMicros;
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

        double getAverageMicros() {
            return averageMicros;
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
                    ", avgTimeMicros=" + String.format("%.2f", averageMicros) +
                    ", attempts=" + attempts +
                    '}';
        }
    }

    static final class WeakScalingResult {
        private final int scaleFactor;
        private final int dataSize;
        private final int iterations;
        private final double averageMicros;

        WeakScalingResult(int scaleFactor, int dataSize, int iterations, double averageMicros) {
            this.scaleFactor = scaleFactor;
            this.dataSize = dataSize;
            this.iterations = iterations;
            this.averageMicros = averageMicros;
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

        double getAverageMicros() {
            return averageMicros;
        }
    }

    static final class StrongScalingResult {
        private final int partitionCount;
        private final int totalSize;
        private final int minPartitionSize;
        private final int maxPartitionSize;
        private final int iterations;
        private final double averageMicros;

        StrongScalingResult(int partitionCount, int totalSize, int minPartitionSize,
                            int maxPartitionSize, int iterations, double averageMicros) {
            this.partitionCount = partitionCount;
            this.totalSize = totalSize;
            this.minPartitionSize = minPartitionSize;
            this.maxPartitionSize = maxPartitionSize;
            this.iterations = iterations;
            this.averageMicros = averageMicros;
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

        double getAverageMicros() {
            return averageMicros;
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
                    "    \"dataSize\": %d,%n    \"sigma\": %.6f,%n    \"iterations\": %d,%n    \"avgTimeMicros\": %.2f,%n    \"attempts\": %d%n",
                    calibration.getDataSize(), calibration.getSigma(), calibration.getIterations(),
                    calibration.getAverageMicros(), calibration.getAttempts()));
            sb.append("  },\n");
            sb.append("  \"weakScaling\": [\n");
            for (int i = 0; i < weakScalingResults.size(); i++) {
                WeakScalingResult result = weakScalingResults.get(i);
                sb.append(String.format(Locale.US,
                        "    {\"scaleFactor\": %d, \"dataSize\": %d, \"iterations\": %d, \"avgTimeMicros\": %.2f}",
                        result.getScaleFactor(), result.getDataSize(), result.getIterations(),
                        result.getAverageMicros()));
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
                        "    {\"partitions\": %d, \"totalSize\": %d, \"minPartitionSize\": %d, \"maxPartitionSize\": %d, \"iterations\": %d, \"avgTimeMicros\": %.2f}",
                        result.getPartitionCount(), result.getTotalSize(), result.getMinPartitionSize(),
                        result.getMaxPartitionSize(), result.getIterations(), result.getAverageMicros()));
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
