package com.benchmark.teaclave.host;

public final class Workload {
    private final int dataSize;
    private final double sigma;
    private final int warmupIterations;
    private final int measureIterations;
    private final int requestedThreads;
    private final int executedThreads;

    public Workload(int dataSize, double sigma, int warmupIterations, int measureIterations,
             int requestedThreads, int executedThreads) {
        this.dataSize = dataSize;
        this.sigma = sigma;
        this.warmupIterations = warmupIterations;
        this.measureIterations = measureIterations;
        this.requestedThreads = requestedThreads;
        this.executedThreads = executedThreads;
    }

    public int getDataSize() {
        return dataSize;
    }

    public double getSigma() {
        return sigma;
    }

    public int getWarmupIterations() {
        return warmupIterations;
    }

    public int getMeasureIterations() {
        return measureIterations;
    }

    public int getRequestedThreads() {
        return requestedThreads;
    }

    public int getExecutedThreads() {
        return executedThreads;
    }

    @Override
    public String toString() {
        return "Workload{" +
                "dataSize=" + dataSize +
                ", sigma=" + sigma +
                ", warmupIterations=" + warmupIterations +
                ", measureIterations=" + measureIterations +
                ", requestedThreads=" + requestedThreads +
                ", executedThreads=" + executedThreads +
                '}';
    }
}
