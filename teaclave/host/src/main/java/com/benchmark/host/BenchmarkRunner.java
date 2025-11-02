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

    Workload prepareWorkload(WorkloadSettings settings, int threadCount) {
        int dataSize = Math.max(1, settings.getDataSize());
        int executedThreads = Math.max(1, threadCount);
        double[] dataset = createDataset(dataSize);

        // create workload based on settings
        return new Workload(dataSize, settings.getSigma(), settings.getWarmupIterations(),
                settings.getMeasureIterations(), threadCount, executedThreads);
    }

    List<WeakScalingResult> runWeakScaling(Workload workload, int[] threadCounts) {
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);
        List<WeakScalingResult> results = new ArrayList<>(counts.length);
        double perThreadWorkload = Math.max(1.0, (double) workload.getDataSize() / workload.getExecutedThreads());
        for (int threads : counts) {
            int executedThreads = Math.max(1, Math.min(threads, maxNativeParallelism));
            int dataSize = Math.max(1, (int) Math.round(perThreadWorkload * executedThreads));
            double[] dataset = createDataset(dataSize);
            double averageMillis = measureAverageMillis(dataset, workload.getSigma(), workload.getWarmupIterations(),
                    workload.getMeasureIterations(), executedThreads);
            results.add(new WeakScalingResult(threads, executedThreads, dataSize, workload.getMeasureIterations(), averageMillis));
        }
        return results;
    }

    List<StrongScalingResult> runStrongScaling(Workload workload, int[] threadCounts) {
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);
        int totalSize = workload.getDataSize();
        double[] baseDataset = createDataset(totalSize);

        List<StrongScalingResult> results = new ArrayList<>(counts.length);
        for (int threads : counts) {
            int executedThreads = Math.max(1, Math.min(threads, maxNativeParallelism));
            double averageMillis = measureAverageMillis(baseDataset, workload.getSigma(), workload.getWarmupIterations(),
                    workload.getMeasureIterations(), executedThreads);
            results.add(new StrongScalingResult(threads, executedThreads, totalSize, workload.getMeasureIterations(), averageMillis));
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

}
