public final class StrongScalingResult {
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
