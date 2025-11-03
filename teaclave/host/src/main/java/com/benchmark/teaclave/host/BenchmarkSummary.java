package com.benchmark.teaclave.host;

import java.util.*;

public final class BenchmarkSummary {
    private final WorkloadSettings settings;
    private final String executionMode;
    private final Workload workload;
    private final int[] weakThreadCounts;
    private final int[] strongThreadCounts;
    private final List<WeakScalingResult> weakScalingResults;
    private final List<StrongScalingResult> strongScalingResults;
    private final int threadsUsed;

    public BenchmarkSummary(WorkloadSettings settings,
                     String executionMode,
                     Workload workload,
                     int[] weakThreadCounts,
                     List<WeakScalingResult> weakScalingResults,
                     int[] strongThreadCounts,
                     List<StrongScalingResult> strongScalingResults,
                     int threadsUsed) {
        this.settings = settings;
        this.executionMode = executionMode;
        this.workload = workload;
        this.weakThreadCounts = Arrays.copyOf(weakThreadCounts, weakThreadCounts.length);
        this.strongThreadCounts = Arrays.copyOf(strongThreadCounts, strongThreadCounts.length);
        this.weakScalingResults = List.copyOf(weakScalingResults);
        this.strongScalingResults = List.copyOf(strongScalingResults);
        this.threadsUsed = threadsUsed;
    }

    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"settings\": {\n");
        sb.append(String.format(Locale.US,
                "    \"sigma\": %.6f,%n    \"dataSize\": %d,%n    \"warmupIterations\": %d,%n    \"measureIterations\": %d,%n    \"weakThreadCounts\": %s,%n    \"strongThreadCounts\": %s,%n",
                settings.getSigma(), settings.getDataSize(), settings.getWarmupIterations(), settings.getMeasureIterations(),
                formatIntArray(weakThreadCounts), formatIntArray(strongThreadCounts)));
        sb.append(String.format(Locale.US, "    \"executionMode\": \"%s\"%n", executionMode));
        sb.append("  },\n");
        sb.append("  \"workload\": {\n");
        sb.append(String.format(Locale.US,
                "    \"dataSize\": %d,%n    \"sigma\": %.6f,%n    \"warmupIterations\": %d,%n    \"measureIterations\": %d,%n    \"requestedThreads\": %d,%n    \"executedThreads\": %d%n",
                workload.getDataSize(), workload.getSigma(), workload.getWarmupIterations(), workload.getMeasureIterations(),
                workload.getRequestedThreads(), workload.getExecutedThreads()));
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
}
