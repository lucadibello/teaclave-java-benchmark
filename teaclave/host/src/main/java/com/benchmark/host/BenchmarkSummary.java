public final class BenchmarkSummary {
    private final WorkloadSettings settings;
    private final String executionMode;
    private final Workload workload;
    private final int[] weakThreadCounts;
    private final int[] strongThreadCounts;
    private final List<WeakScalingResult> weakScalingResults;
    private final List<StrongScalingResult> strongScalingResults;
    private final int maxNativeThreadsSupported;
    private final int maxNativeThreadsUsed;

    BenchmarkSummary(WorkloadSettings settings,
                      String executionMode,
                      Workload workload,
                      int[] weakThreadCounts,
                      List<WeakScalingResult> weakScalingResults,
                      int[] strongThreadCounts,
                      List<StrongScalingResult> strongScalingResults,
                      int maxNativeThreadsUsed) {
        this.settings = settings;
        this.executionMode = executionMode;
        this.workload = workload;
        this.weakThreadCounts = Arrays.copyOf(weakThreadCounts, weakThreadCounts.length);
        this.strongThreadCounts = Arrays.copyOf(strongThreadCounts, strongThreadCounts.length);
        this.weakScalingResults = Collections.unmodifiableList(new ArrayList<>(weakScalingResults));
        this.strongScalingResults = Collections.unmodifiableList(new ArrayList<>(strongScalingResults));
        this.maxNativeThreadsSupported = computeMaxNativeThreadsSupported(workload, weakScalingResults, strongScalingResults);
        this.maxNativeThreadsUsed = maxNativeThreadsUsed;
    }

    Workload getWorkload() {
        return workload;
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
                "    \"sigma\": %.6f,%n    \"dataSize\": %d,%n    \"warmupIterations\": %d,%n    \"measureIterations\": %d,%n    \"weakThreadCounts\": %s,%n    \"strongThreadCounts\": %s,%n",
                settings.getSigma(), settings.getDataSize(), settings.getWarmupIterations(), settings.getMeasureIterations(),
                formatIntArray(weakThreadCounts), formatIntArray(strongThreadCounts)));
        sb.append(String.format(Locale.US, "    \"executionMode\": \"%s\",%n", executionMode));
        sb.append(String.format(Locale.US, "    \"maxNativeThreadsSupported\": %d,%n", maxNativeThreadsSupported));
        sb.append(String.format(Locale.US, "    \"maxNativeThreadsUsed\": %d%n", maxNativeThreadsUsed));
        sb.append("  },\n");
        sb.append("  \"workload\": {\n");
        sb.append(String.format(Locale.US,
                "    \"dataSize\": %d,%n    \"sigma\": %.6f,%n    \"warmupIterations\": %d,%n    \"measureIterations\": %d,%n    \"avgTimeMillis\": %.3f,%n    \"requestedThreads\": %d,%n    \"executedThreads\": %d%n",
                workload.getDataSize(), workload.getSigma(), workload.getWarmupIterations(), workload.getMeasureIterations(),
                workload.getAverageMillis(), workload.getRequestedThreads(), workload.getExecutedThreads()));
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

    private static int computeMaxNativeThreadsSupported(Workload workload,
                                                        List<WeakScalingResult> weakResults,
                                                        List<StrongScalingResult> strongResults) {
        int max = workload != null ? workload.getExecutedThreads() : 0;
        for (WeakScalingResult result : weakResults) {
            max = Math.max(max, result.getExecutedThreadCount());
        }
        for (StrongScalingResult result : strongResults) {
            max = Math.max(max, result.getExecutedThreadCount());
        }
        return max;
    }
}
