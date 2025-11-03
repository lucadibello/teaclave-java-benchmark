package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class BenchmarkRunner {

    private static final double NANOS_IN_MILLI = 1_000_000.0;

    private final Service service;
    private final Random random;

    public BenchmarkRunner(Service service) {
        this(service, new Random(0L));
    }

    public BenchmarkRunner(Service service, Random random) {
        // delegate to the new ctor that accepts a maxThreads bound (default = available processors)
        this(service, random, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    // New constructor: allows caller to specify the maximum number of worker threads that the executor can create.
    public BenchmarkRunner(Service service, Random random, int maxThreads) {
        this.service = service;
        this.random = random;
    }

    public Workload prepareWorkload(WorkloadSettings settings, int threadCount) {
        int dataSize = Math.max(1, settings.getDataSize());
        int executedThreads = Math.max(1, threadCount); // min threads = 1

        // return workload with specified settings
        return new Workload(dataSize, settings.getSigma(), settings.getWarmupIterations(),
                settings.getMeasureIterations(), threadCount, executedThreads);
    }

    public List<WeakScalingResult> runWeakScaling(Workload workload, int[] threadCounts) {
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);
        List<WeakScalingResult> results = new ArrayList<>(counts.length);
        double perThreadWorkload = Math.max(1.0, (double) workload.getDataSize() / workload.getExecutedThreads());
        for (int threads : counts) {
            int executedThreads = Math.max(1, threads);
            int dataSize = Math.max(1, (int) Math.round(perThreadWorkload * executedThreads));

            double[] dataset = createDataset(dataSize);
            double averageMillis = measureAverageMillis(
                dataset,
                workload.getSigma(),
                workload.getWarmupIterations(),
                workload.getMeasureIterations(),
                executedThreads
            );

            // record result
            results.add(
                new WeakScalingResult(
                    threads,
                    executedThreads,
                    dataSize,
                    workload.getMeasureIterations(),
                    averageMillis
                )
            );
        }
        return results;
    }

    public List<StrongScalingResult> runStrongScaling(Workload workload, int[] threadCounts) {
        // copy locally and sort
        int[] counts = Arrays.copyOf(threadCounts, threadCounts.length);
        Arrays.sort(counts);

        // prepare base dataset
        int totalSize = workload.getDataSize();
        double[] baseDataset = createDataset(totalSize);

        List<StrongScalingResult> results = new ArrayList<>(counts.length);
        for (int threads : counts) {
            int executedThreads = Math.max(1, threads); // min threads = 1

            // run test + compute avg ms
            double averageMillis = measureAverageMillis(
                baseDataset,
                workload.getSigma(),
                workload.getWarmupIterations(),
                workload.getMeasureIterations(),
                executedThreads
            );

            // record result
            results.add(
                new StrongScalingResult(
                    threads,
                    executedThreads,
                    totalSize,
                    workload.getMeasureIterations(),
                    averageMillis
                )
            );
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
        final int effectiveThreads = Math.max(1, threadCount);
        service.initBinaryAggregation(dataset.length, sigma);

        final int chunkSize = Math.max(1, (dataset.length + effectiveThreads - 1) / effectiveThreads);

        System.out.println("Creating " + effectiveThreads + " worker threads");
        final Thread[] workers = new Thread[effectiveThreads];
        final AtomicReference<Throwable> failure = new AtomicReference<>(null);

        try {
            // start threads
            for (int t = 0; t < effectiveThreads; t++) {
                final int start = t * chunkSize;
                final int end = Math.min(dataset.length, start + chunkSize);
                if (start >= end) {
                    workers[t] = null;
                    continue;
                }
                final int sliceStart = start;
                final int sliceEnd = end;

                ThreadFactory threadFactory = new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger();
                    @Override public Thread newThread(Runnable r) {
                        Thread th = new Thread(r);
                        th.setName("benchmark-worker-" + counter.getAndIncrement());
                        th.setDaemon(false);
                        return th;
                    }
                };
                Thread worker = threadFactory.newThread(() -> {
                    try {
                        for (int idx = sliceStart; idx < sliceEnd; idx++) {
                            // bail out early if another worker failed or current thread is interrupted
                            if (Thread.currentThread().isInterrupted() || failure.get() != null) break;
                            service.addToBinaryAggregation(dataset[idx]);
                        }
                    } catch (Throwable ex) {
                        // capture the first failure
                        failure.compareAndSet(null, ex);
                        // if desired, re-interrupt the thread (best-effort)
                        Thread.currentThread().interrupt();
                    }
                });
                workers[t] = worker;
                worker.start();
            }

            // wait for completion
            try {
                for (Thread w : workers) {
                    if (w == null) continue;
                    try {
                        w.join();
                    } catch (InterruptedException ie) {
                        // preserve interrupt status and attempt to stop workers
                        Thread.currentThread().interrupt();
                        // interrupt remaining workers
                        for (Thread other : workers) {
                            if (other != null && other.isAlive()) {
                                try { other.interrupt(); } catch (RuntimeException ignore) { /* best-effort */ }
                            }
                        }
                        // join them after interrupt
                        for (Thread other : workers) {
                            if (other == null) continue;
                            try { other.join(); } catch (InterruptedException ignore) { /* best-effort */ }
                        }
                        throw new IllegalStateException("Benchmark interrupted", ie);
                    }
                }
            } catch (IllegalStateException ise) {
                throw ise;
            }

            // check for worker failure
            Throwable workerFailure = failure.get();
            if (workerFailure != null) {
                if (workerFailure instanceof RuntimeException) throw (RuntimeException) workerFailure;
                throw new IllegalStateException("Worker thread failed", workerFailure);
            }

            final double total = service.getBinaryAggregationSum();
            perturbDataset(dataset);
            if (Double.isNaN(total)) {
                throw new IllegalStateException("Aggregation sum produced NaN");
            }
            return total;
        } finally {
            // Ensure all worker threads are not left running; best-effort interruption and join.
            for (Thread w : workers) {
                if (w == null) continue;
                if (w.isAlive()) {
                    try { w.interrupt(); } catch (RuntimeException ignore) { /* best-effort */ }
                }
            }
            for (Thread w : workers) {
                if (w == null) continue;
                try { w.join(100); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    // if join is interrupted, continue best-effort cleanup
                }
            }
            // drop references
        }
    }
}
