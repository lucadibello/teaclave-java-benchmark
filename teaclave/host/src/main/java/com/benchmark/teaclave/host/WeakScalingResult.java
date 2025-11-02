package com.benchmark.teaclave.host;

public final class WeakScalingResult {
    private final int requestedThreadCount;
    private final int executedThreadCount;
    private final int dataSize;
    private final int iterations;
    private final double averageMillis;

    public WeakScalingResult(int requestedThreadCount, int executedThreadCount, int dataSize, int iterations, double averageMillis) {
        this.requestedThreadCount = requestedThreadCount;
        this.executedThreadCount = executedThreadCount;
        this.dataSize = dataSize;
        this.iterations = iterations;
        this.averageMillis = averageMillis;
    }

    public int getRequestedThreadCount() {
        return requestedThreadCount;
    }

    public int getExecutedThreadCount() {
        return executedThreadCount;
    }

    public int getDataSize() {
        return dataSize;
    }

    public int getIterations() {
        return iterations;
    }

    public double getAverageMillis() {
        return averageMillis;
    }
}
