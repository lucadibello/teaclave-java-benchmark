package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class BenchmarkRunner implements AutoCloseable {

    private static final double NANOS_IN_MILLI = 1_000_000.0;

    private final Service service;
    private final Random random;
    private final int maxNativeParallelism;
    private final ExecutorService executor;

    BenchmarkRunner(Service service) {
        this(service, new Random(0L));
    }

    BenchmarkRunner(Service service, int maxNativeParallelism) {
        this(service, new Random(0L), maxNativeParallelism);
    }

    BenchmarkRunner(Service service, Random random) {
        this(service, random, Integer.MAX_VALUE);
    }

    BenchmarkRunner(Service service, Random random, int maxNativeParallelism) {
        this.service = service;
        this.random = random;
        this.maxNativeParallelism = maxNativeParallelism <= 0 ? Integer.MAX_VALUE : maxNativeParallelism;
        this.executor = createExecutor(this.maxNativeParallelism);
    }

    int getMaxNativeParallelism() {
        return maxNativeParallelism;
    }

    CalibratedWorkload calibrate(CalibrationSettings settings, int threadCount) {
        int size = settings.getInitialSize();
        int attempts = 0;
        double averageMillis = 0.0;
        double[] dataset = createDataset(size);

        int executedThreads = Math.max(1, Math.min(threadCount, maxNativeParallelism));

        while (size <= settings.getMaxSize()) {
            averageMillis = measureAverageMillis(dataset, settings.getSigma(), settings.getWarmupIterations(),
                    settings.getMeasureIterations(), executedThreads);
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
                averageMillis, attempts, threadCount, executedThreads);
    }

    List<WeakScalingResult> runWeakScaling(CalibratedWorkload workload, int[] threadCounts, int iterations) {
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);
        List<WeakScalingResult> results = new ArrayList<>(counts.length);
        double perThreadWorkload = Math.max(1.0, (double) workload.getDataSize() / workload.getExecutedThreads());
        for (int threads : counts) {
            int executedThreads = Math.max(1, Math.min(threads, maxNativeParallelism));
            int dataSize = Math.max(1, (int) Math.round(perThreadWorkload * executedThreads));
            double[] dataset = createDataset(dataSize);
            double averageMillis = measureAverageMillis(dataset, workload.getSigma(), 1, iterations, executedThreads);
            results.add(new WeakScalingResult(threads, executedThreads, dataSize, iterations, averageMillis));
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
            int executedThreads = Math.max(1, Math.min(threads, maxNativeParallelism));
            double averageMillis = measureAverageMillis(baseDataset, workload.getSigma(), 1, iterations, executedThreads);
            results.add(new StrongScalingResult(threads, executedThreads, totalSize, iterations, averageMillis));
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
        int effectiveThreads = Math.max(1, Math.min(threadCount, maxNativeParallelism));
        service.initBinaryAggregation(dataset.length, sigma);
        int chunkSize = Math.max(1, (dataset.length + effectiveThreads - 1) / effectiveThreads);
        List<Future<?>> futures = new ArrayList<>(effectiveThreads);
        for (int t = 0; t < effectiveThreads; t++) {
            int start = t * chunkSize;
            int end = Math.min(dataset.length, start + chunkSize);
            if (start >= end) {
                break;
            }
            final int sliceStart = start;
            final int sliceEnd = end;
            futures.add(executor.submit(() -> {
                for (int idx = sliceStart; idx < sliceEnd; idx++) {
                    service.addToBinaryAggregation(dataset[idx]);
                }
            }));
        }

        Throwable failure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cancelFutures(futures);
                throw new IllegalStateException("Benchmark interrupted", ie);
            } catch (ExecutionException ee) {
                failure = ee.getCause() == null ? ee : ee.getCause();
                cancelFutures(futures);
                break;
            }
        }

        if (failure != null) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw new IllegalStateException("Worker thread failed", failure);
        }

        double total = service.getBinaryAggregationSum();
        perturbDataset(dataset);
        if (Double.isNaN(total)) {
            throw new IllegalStateException("Aggregation sum produced NaN");
        }
        return total;
    }

    private ExecutorService createExecutor(int nativeParallelism) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("benchmark-worker-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        if (nativeParallelism == Integer.MAX_VALUE) {
            ThreadPoolExecutor cached = new ThreadPoolExecutor(
                    0,
                    Integer.MAX_VALUE,
                    60L,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    threadFactory);
            cached.allowCoreThreadTimeOut(true);
            return cached;
        }
        ThreadPoolExecutor fixed = new ThreadPoolExecutor(
                nativeParallelism,
                nativeParallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory);
        fixed.prestartAllCoreThreads();
        return fixed;
    }

    private void cancelFutures(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
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
        private final int requestedThreads;
        private final int executedThreads;

        CalibratedWorkload(int dataSize, double sigma, int iterations,
                           double averageMillis, int attempts, int requestedThreads, int executedThreads) {
            this.dataSize = dataSize;
            this.sigma = sigma;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
            this.attempts = attempts;
            this.requestedThreads = requestedThreads;
            this.executedThreads = executedThreads;
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

        int getRequestedThreads() {
            return requestedThreads;
        }

        int getExecutedThreads() {
            return executedThreads;
        }

        @Override
        public String toString() {
            return "CalibratedWorkload{" +
                    "dataSize=" + dataSize +
                    ", sigma=" + sigma +
                    ", iterations=" + iterations +
                    ", avgTimeMillis=" + String.format("%.3f", averageMillis) +
                    ", attempts=" + attempts +
                    ", requestedThreads=" + requestedThreads +
                    ", executedThreads=" + executedThreads +
                    '}';
        }
    }

    static final class WeakScalingResult {
        private final int requestedThreadCount;
        private final int executedThreadCount;
        private final int dataSize;
        private final int iterations;
        private final double averageMillis;

        WeakScalingResult(int requestedThreadCount, int executedThreadCount, int dataSize, int iterations, double averageMillis) {
            this.requestedThreadCount = requestedThreadCount;
            this.executedThreadCount = executedThreadCount;
            this.dataSize = dataSize;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
        }

        int getRequestedThreadCount() {
            return requestedThreadCount;
        }

        int getExecutedThreadCount() {
            return executedThreadCount;
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
        private final int requestedThreadCount;
        private final int executedThreadCount;
        private final int totalSize;
        private final int iterations;
        private final double averageMillis;

        StrongScalingResult(int requestedThreadCount, int executedThreadCount, int totalSize, int iterations, double averageMillis) {
            this.requestedThreadCount = requestedThreadCount;
            this.executedThreadCount = executedThreadCount;
            this.totalSize = totalSize;
            this.iterations = iterations;
            this.averageMillis = averageMillis;
        }

        int getRequestedThreadCount() {
            return requestedThreadCount;
        }

        int getExecutedThreadCount() {
            return executedThreadCount;
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
        private final int maxNativeThreadsSupported;
        private final int maxNativeThreadsUsed;

        BenchmarkSummary(CalibrationSettings settings,
                         String executionMode,
                         CalibratedWorkload calibration,
                         int[] weakThreadCounts,
                         List<WeakScalingResult> weakScalingResults,
                         int[] strongThreadCounts,
                         List<StrongScalingResult> strongScalingResults,
                         int maxNativeThreadsUsed) {
            this.settings = settings;
            this.executionMode = executionMode;
            this.calibration = calibration;
            this.weakThreadCounts = Arrays.copyOf(weakThreadCounts, weakThreadCounts.length);
            this.strongThreadCounts = Arrays.copyOf(strongThreadCounts, strongThreadCounts.length);
            this.weakScalingResults = Collections.unmodifiableList(new ArrayList<>(weakScalingResults));
            this.strongScalingResults = Collections.unmodifiableList(new ArrayList<>(strongScalingResults));
            this.maxNativeThreadsSupported = computeMaxNativeThreadsSupported(calibration, weakScalingResults, strongScalingResults);
            this.maxNativeThreadsUsed = maxNativeThreadsUsed;
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
                    "    \"sigma\": %.6f,%n    \"initialSize\": %d,%n    \"maxSize\": %d,%n    \"targetMillis\": %.3f,%n    \"growthFactor\": %d,%n    \"warmupIterations\": %d,%n    \"measureIterations\": %d,%n    \"weakThreadCounts\": %s,%n    \"strongThreadCounts\": %s,%n",
                    settings.getSigma(), settings.getInitialSize(), settings.getMaxSize(), settings.getTargetMillis(),
                    settings.getGrowthFactor(), settings.getWarmupIterations(), settings.getMeasureIterations(),
                    formatIntArray(weakThreadCounts), formatIntArray(strongThreadCounts)));
            sb.append(String.format(Locale.US, "    \"executionMode\": \"%s\",%n", executionMode));
            sb.append(String.format(Locale.US, "    \"maxNativeThreadsSupported\": %d,%n", maxNativeThreadsSupported));
            sb.append(String.format(Locale.US, "    \"maxNativeThreadsUsed\": %d%n", maxNativeThreadsUsed));
            sb.append("  },\n");
            sb.append("  \"calibration\": {\n");
            sb.append(String.format(Locale.US,
                    "    \"dataSize\": %d,%n    \"sigma\": %.6f,%n    \"iterations\": %d,%n    \"avgTimeMillis\": %.3f,%n    \"attempts\": %d,%n    \"requestedThreads\": %d,%n    \"executedThreads\": %d%n",
                    calibration.getDataSize(), calibration.getSigma(), calibration.getIterations(),
                    calibration.getAverageMillis(), calibration.getAttempts(), calibration.getRequestedThreads(),
                    calibration.getExecutedThreads()));
            sb.append("  },\n");
            sb.append("  \"weakScaling\": [\n");
            for (int i = 0; i < weakScalingResults.size(); i++) {
                WeakScalingResult result = weakScalingResults.get(i);
                sb.append(String.format(Locale.US,
                        "    {\"threads\": %d, \"executedThreads\": %d, \"dataSize\": %d, \"iterations\": %d, \"avgTimeMillis\": %.3f}",
                        result.getRequestedThreadCount(), result.getExecutedThreadCount(), result.getDataSize(), result.getIterations(),
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
                        "    {\"threads\": %d, \"executedThreads\": %d, \"totalSize\": %d, \"iterations\": %d, \"avgTimeMillis\": %.3f}",
                        result.getRequestedThreadCount(), result.getExecutedThreadCount(), result.getTotalSize(), result.getIterations(), result.getAverageMillis()));
                if (i < strongScalingResults.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}");
            return sb.toString();
        }

        int getMaxNativeThreadsSupported() {
            return maxNativeThreadsSupported;
        }

        int getMaxNativeThreadsUsed() {
            return maxNativeThreadsUsed;
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

        private static int computeMaxNativeThreadsSupported(CalibratedWorkload calibration,
                                                            List<WeakScalingResult> weakResults,
                                                            List<StrongScalingResult> strongResults) {
            int max = calibration != null ? calibration.getExecutedThreads() : 0;
            for (WeakScalingResult result : weakResults) {
                max = Math.max(max, result.getExecutedThreadCount());
            }
            for (StrongScalingResult result : strongResults) {
                max = Math.max(max, result.getExecutedThreadCount());
            }
            return max;
        }
    }

}
