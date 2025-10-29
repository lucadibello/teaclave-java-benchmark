package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

    CalibratedWorkload calibrate(CalibrationSettings settings, int threadCount) {
        int size = settings.getInitialSize();
        int attempts = 0;
        double averageMillis = 0.0;
        double[] dataset = createDataset(size);

        while (size <= settings.getMaxSize()) {
            averageMillis = measureAverageMillis(dataset, settings.getSigma(), settings.getWarmupIterations(),
                    settings.getMeasureIterations(), threadCount);
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
                averageMillis, attempts, threadCount);
    }

    List<WeakScalingResult> runWeakScaling(CalibratedWorkload workload, int[] threadCounts, int iterations) {
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);
        List<WeakScalingResult> results = new ArrayList<>(counts.length);
        double perThreadWorkload = Math.max(1.0, (double) workload.getDataSize() / workload.getCalibrationThreads());
        for (int threads : counts) {
            int dataSize = Math.max(1, (int) Math.round(perThreadWorkload * threads));
            double[] dataset = createDataset(dataSize);
            double averageMillis = measureAverageMillis(dataset, workload.getSigma(), 1, iterations, threads);
            results.add(new WeakScalingResult(threads, dataSize, iterations, averageMillis));
        }
        return results;
    }

    List<StrongScalingResult> runStrongScaling(CalibratedWorkload workload, int[] threadCounts, int iterations) {
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);
        int totalSize = workload.getDataSize();
        double[] baseDataset = createDataset(totalSize);

        List<StrongScalingResult> results = new ArrayList<>(counts.length);
        for (int threads : counts) {
            double averageMillis = measureAverageMillis(baseDataset, workload.getSigma(), 1, iterations, threads);
            results.add(new StrongScalingResult(threads, totalSize, iterations, averageMillis));
        }
        return results;
    }

    private double measureAverageMillis(double[] dataset, double sigma, int warmupIterations,
                                        int measureIterations, int threadCount) {
        if (warmupIterations > 0) {
            double[] warmupCopy = Arrays.copyOf(dataset, dataset.length);
            executeIterations(warmupCopy, sigma, warmupIterations, threadCount);
        }
        double[] measureCopy = Arrays.copyOf(dataset, dataset.length);
        return executeIterations(measureCopy, sigma, measureIterations, threadCount);
    }

    private double executeIterations(double[] dataset, double sigma, int iterations, int threadCount) {
        long start = System.nanoTime();
        double lastResult = 0.0;
        for (int i = 0; i < iterations; i++) {
            lastResult = runDataset(dataset, sigma, threadCount);
        }
        long duration = System.nanoTime() - start;
        double averageMillis = duration / (iterations * NANOS_IN_MILLI);
        if (Double.isNaN(lastResult)) {
            throw new IllegalStateException("Computation produced NaN");
        }
        return averageMillis;
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

    private double runDataset(double[] dataset, double sigma, int threadCount) {
        service.initBinaryAggregation(dataset.length, sigma);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, new BenchmarkThreadFactory());
        try {
            List<Callable<Double>> tasks = new ArrayList<>(threadCount);
            int chunkSize = Math.max(1, (dataset.length + threadCount - 1) / threadCount);
            for (int t = 0; t < threadCount; t++) {
                int start = t * chunkSize;
                int end = Math.min(dataset.length, start + chunkSize);
                if (start >= end) {
                    break;
                }
                tasks.add(new SliceTask(service, dataset, start, end));
            }
            double lastResult = Double.NaN;
            for (Future<Double> future : pool.invokeAll(tasks)) {
                Double result = future.get();
                if (result != null) {
                    lastResult = result;
                }
            }
            double total = service.getBinaryAggregationSum();
            perturbDataset(dataset);
            if (Double.isNaN(total)) {
                throw new IllegalStateException("Aggregation sum produced NaN");
            }
            return total;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating aggregation tree", ie);
        } catch (ExecutionException ee) {
            throw new IllegalStateException("Failed to update aggregation tree", ee.getCause());
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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
        private final int calibrationThreads;

        CalibratedWorkload(int dataSize, double sigma, int iterations,
                           double averageMillis, int attempts, int calibrationThreads) {
            this.dataSize = dataSize;
            this.sigma = sigma;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
            this.attempts = attempts;
            this.calibrationThreads = calibrationThreads;
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

        int getCalibrationThreads() {
            return calibrationThreads;
        }

        @Override
        public String toString() {
            return "CalibratedWorkload{" +
                    "dataSize=" + dataSize +
                    ", sigma=" + sigma +
                    ", iterations=" + iterations +
                    ", avgTimeMillis=" + String.format("%.3f", averageMillis) +
                    ", attempts=" + attempts +
                    ", calibrationThreads=" + calibrationThreads +
                    '}';
        }
    }

    static final class WeakScalingResult {
        private final int threadCount;
        private final int dataSize;
        private final int iterations;
        private final double averageMillis;

        WeakScalingResult(int threadCount, int dataSize, int iterations, double averageMillis) {
            this.threadCount = threadCount;
            this.dataSize = dataSize;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
        }

        int getThreadCount() {
            return threadCount;
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
        private final int threadCount;
        private final int totalSize;
        private final int iterations;
        private final double averageMillis;

        StrongScalingResult(int threadCount, int totalSize, int iterations, double averageMillis) {
            this.threadCount = threadCount;
            this.totalSize = totalSize;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
        }

        int getThreadCount() {
            return threadCount;
        }

        int getTotalSize() {
            return totalSize;
        }

        int getIterations() {
            return iterations;
        }

        double getAverageMillis() {
            return averageMillis;
        }
    }

    static final class BenchmarkSummary {
        private final CalibrationSettings settings;
        private final String executionMode;
        private final CalibratedWorkload calibration;
        private final int[] weakThreadCounts;
        private final int[] strongThreadCounts;
        private final List<WeakScalingResult> weakScalingResults;
        private final List<StrongScalingResult> strongScalingResults;

        BenchmarkSummary(CalibrationSettings settings,
                         String executionMode,
                         CalibratedWorkload calibration,
                         int[] weakThreadCounts,
                         List<WeakScalingResult> weakScalingResults,
                         int[] strongThreadCounts,
                         List<StrongScalingResult> strongScalingResults) {
            this.settings = settings;
            this.executionMode = executionMode;
            this.calibration = calibration;
            this.weakThreadCounts = Arrays.copyOf(weakThreadCounts, weakThreadCounts.length);
            this.strongThreadCounts = Arrays.copyOf(strongThreadCounts, strongThreadCounts.length);
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
            sb.append("  \"settings\": {\n");
            sb.append(String.format(Locale.US,
                    "    \"sigma\": %.6f,%n    \"initialSize\": %d,%n    \"maxSize\": %d,%n    \"targetMillis\": %.3f,%n    \"growthFactor\": %d,%n    \"warmupIterations\": %d,%n    \"measureIterations\": %d,%n    \"weakThreadCounts\": %s,%n    \"strongThreadCounts\": %s,%n    \"executionMode\": \"%s\"%n",
                    settings.getSigma(), settings.getInitialSize(), settings.getMaxSize(), settings.getTargetMillis(),
                    settings.getGrowthFactor(), settings.getWarmupIterations(), settings.getMeasureIterations(),
                    formatIntArray(weakThreadCounts), formatIntArray(strongThreadCounts), executionMode));
            sb.append("  },\n");
            sb.append("  \"calibration\": {\n");
            sb.append(String.format(Locale.US,
                    "    \"dataSize\": %d,%n    \"sigma\": %.6f,%n    \"iterations\": %d,%n    \"avgTimeMillis\": %.3f,%n    \"attempts\": %d,%n    \"threads\": %d%n",
                    calibration.getDataSize(), calibration.getSigma(), calibration.getIterations(),
                    calibration.getAverageMillis(), calibration.getAttempts(), calibration.getCalibrationThreads()));
            sb.append("  },\n");
            sb.append("  \"weakScaling\": [\n");
            for (int i = 0; i < weakScalingResults.size(); i++) {
                WeakScalingResult result = weakScalingResults.get(i);
                sb.append(String.format(Locale.US,
                        "    {\"threads\": %d, \"dataSize\": %d, \"iterations\": %d, \"avgTimeMillis\": %.3f}",
                        result.getThreadCount(), result.getDataSize(), result.getIterations(),
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
                        "    {\"threads\": %d, \"totalSize\": %d, \"iterations\": %d, \"avgTimeMillis\": %.3f}",
                        result.getThreadCount(), result.getTotalSize(), result.getIterations(), result.getAverageMillis()));
                if (i < strongScalingResults.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}");
            return sb.toString();
        }

        private static String formatIntArray(int[] values) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < values.length; i++) {
                builder.append(values[i]);
                if (i < values.length - 1) {
                    builder.append(",");
                }
            }
            builder.append("]");
            return builder.toString();
        }
    }

    private static final class BenchmarkThreadFactory implements ThreadFactory {
        private int index = 0;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "benchmark-executor-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class SliceTask implements Callable<Double> {
        private final Service service;
        private final double[] dataset;
        private final int start;
        private final int end;

        SliceTask(Service service, double[] dataset, int start, int end) {
            this.service = service;
            this.dataset = dataset;
            this.start = start;
            this.end = end;
        }

        @Override
        public Double call() {
            Double last = null;
            for (int i = start; i < end; i++) {
                last = service.addToBinaryAggregation(dataset[i]);
            }
            return last;
        }
    }
}
