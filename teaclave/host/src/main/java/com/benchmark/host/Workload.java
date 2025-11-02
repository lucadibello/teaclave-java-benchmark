public static final class Workload {
    private final int dataSize;
    private final double sigma;
    private final int warmupIterations;
    private final int measureIterations;
    private final double averageMillis;
    private final int requestedThreads;
    private final int executedThreads;

    Workload(int dataSize, double sigma, int warmupIterations, int measureIterations,
              int requestedThreads, int executedThreads) {
        this.dataSize = dataSize;
        this.sigma = sigma;
        this.warmupIterations = warmupIterations;
        this.measureIterations = measureIterations;
        this.requestedThreads = requestedThreads;
        this.executedThreads = executedThreads;
    }

    int getDataSize() {
        return dataSize;
    }

    double getSigma() {
        return sigma;
    }

    int getWarmupIterations() {
        return warmupIterations;
    }

    int getMeasureIterations() {
        return measureIterations;
    }

    int getRequestedThreads() {
        return requestedThreads;
    }

    int getExecutedThreads() {
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
