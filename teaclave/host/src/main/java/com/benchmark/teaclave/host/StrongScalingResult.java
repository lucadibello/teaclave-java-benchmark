package com.benchmark.teaclave.host;

public final class StrongScalingResult {
    private final int requestedThreadCount;
    private final int executedThreadCount;
    private final int totalSize;
    private final int iterations;
    private final double averageMillis;

    public StrongScalingResult(int requestedThreadCount, int executedThreadCount, int totalSize, int iterations, double averageMillis) {
        this.requestedThreadCount = requestedThreadCount;
        this.executedThreadCount = executedThreadCount;
        this.totalSize = totalSize;
        this.iterations = iterations;
        this.averageMillis = averageMillis;
    }

    public int getRequestedThreadCount() {
        return requestedThreadCount;
    }

    public int getExecutedThreadCount() {
        return executedThreadCount;
    }

    public int getTotalSize() {
        return totalSize;
    }

    public int getIterations() {
        return iterations;
    }

    public double getAverageMillis() {
        return averageMillis;
    }
}
