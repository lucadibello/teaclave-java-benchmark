package com.benchmark.teaclave.host;

public final class WorkloadSettings {
    private static final String ENV_DATA_SIZE = "TEACLAVE_BENCH_DATA_SIZE";
    private static final String ENV_WARMUP = "TEACLAVE_BENCH_WARMUP";
    private static final String ENV_MEASURE = "TEACLAVE_BENCH_MEASURE";

    private final int dataSize;
    private final int warmupIterations;
    private final int measureIterations;
    private final double sigma;

    public WorkloadSettings(int dataSize,
                     int warmupIterations,
                     int measureIterations,
                     double sigma) {
        this.dataSize = dataSize;
        this.warmupIterations = warmupIterations;
        this.measureIterations = measureIterations;
        this.sigma = sigma;
    }

    public static WorkloadSettings fromEnvironment(double sigma) {
        int dataSize = parseIntEnv(ENV_DATA_SIZE, 1024);
        int warmupIterations = parseIntEnv(ENV_WARMUP, 3);
        int measureIterations = parseIntEnv(ENV_MEASURE, 5);
        return new WorkloadSettings(dataSize, warmupIterations, measureIterations, sigma);
    }

    public int getDataSize() {
        return dataSize;
    }

    public int getWarmupIterations() {
        return warmupIterations;
    }

    public int getMeasureIterations() {
        return measureIterations;
    }

    public double getSigma() {
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
}
