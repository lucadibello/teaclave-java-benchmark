public final class WeakScalingResult {
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
