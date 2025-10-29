package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.host.exception.EnclaveDestroyingException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Random;

class BinaryAggregationBenchmarkTest {

    private static Enclave enclave;
    private static Service service;
    private static boolean enclaveLoaded;

    @BeforeAll
    static void setUp() throws Exception {
        enclave = EnclaveFactory.create(EnclaveType.MOCK_IN_JVM);
        Iterator<Service> services = enclave.load(Service.class);
        if (!services.hasNext()) {
            Assumptions.assumeTrue(false, "Service implementation not found inside enclave");
        }
        service = services.next();
        enclaveLoaded = true;
    }

    @AfterAll
    static void tearDown() throws EnclaveDestroyingException {
        if (enclaveLoaded && enclave != null) {
            enclave.destroy();
        }
    }

    @Test
    void aggregationMatchesSumWhenSigmaIsZero() {
        double[] data = createDataset(1024);
        double expected = plainSum(data);
        service.initBinaryAggregation(data.length, 0.0);
        double actual = 0.0;
        for (double value : data) {
            actual = service.addToBinaryAggregation(value);
        }
        actual = service.getBinaryAggregationSum();
        Assertions.assertEquals(expected, actual, 1e-6, "Private sum should equal real sum without noise");
    }

    @Test
    void benchmarkMultipleIterations() {
        int[] weakThreads = new int[]{1, 2, 4};
        int[] strongThreads = new int[]{1, 2, 4};
        BenchmarkRunner.WorkloadSettings settings =
                new BenchmarkRunner.WorkloadSettings(512, 1, 3, 0.25);
        try (BenchmarkRunner runner = new BenchmarkRunner(service, new Random(456L))) {
            BenchmarkRunner.Workload workload = runner.prepareWorkload(settings, 1);
            var weakResults = runner.runWeakScaling(workload, weakThreads);
            var strongResults = runner.runStrongScaling(workload, strongThreads);
            BenchmarkRunner.BenchmarkSummary summary =
                    new BenchmarkRunner.BenchmarkSummary(settings, EnclaveType.MOCK_IN_JVM.name(),
                            workload, weakThreads, weakResults, strongThreads, strongResults,
                            runner.getMaxNativeParallelism());

            Assertions.assertEquals(weakThreads.length, weakResults.size());
            Assertions.assertEquals(strongThreads.length, strongResults.size());
            Assertions.assertTrue(summary.toPrettyString().contains("\"weakScaling\""));
            weakResults.forEach(result -> Assertions.assertTrue(
                    Double.isFinite(result.getAverageMillis()),
                    "Weak scaling result should be finite"));
            strongResults.forEach(result -> Assertions.assertTrue(
                    Double.isFinite(result.getAverageMillis()),
                    "Strong scaling result should be finite"));
        }
    }

    @Test
    void workloadMatchesConfiguration() {
        BenchmarkRunner.WorkloadSettings settings =
                new BenchmarkRunner.WorkloadSettings(256, 1, 2, 0.0);
        try (BenchmarkRunner runner = new BenchmarkRunner(service, new Random(123L))) {
            BenchmarkRunner.Workload workload = runner.prepareWorkload(settings, 1);
            Assertions.assertEquals(256, workload.getDataSize(), "Workload size should match the configured size");
            Assertions.assertTrue(workload.getAverageMillis() >= 0.0, "Average millis should be non-negative");
        }
    }

    @Test
    void weakScalingRespectsNativeParallelismLimit() {
        int[] weakThreads = new int[]{1, 2, 4};
        BenchmarkRunner.WorkloadSettings settings =
                new BenchmarkRunner.WorkloadSettings(128, 1, 2, 0.1);
        try (BenchmarkRunner runner = new BenchmarkRunner(service, new Random(789L), 2)) {
            BenchmarkRunner.Workload workload = runner.prepareWorkload(settings, 1);
            var weakResults = runner.runWeakScaling(workload, weakThreads);

            WeakScalingResultLookup lookup = new WeakScalingResultLookup(weakResults);
            var twoThreads = lookup.byRequestedCount(2);
            var fourThreads = lookup.byRequestedCount(4);

            Assertions.assertEquals(2, twoThreads.getExecutedThreadCount(), "Executed threads should match native limit for 2 threads");
            Assertions.assertEquals(2, fourThreads.getExecutedThreadCount(), "Executed threads should be capped at native limit for 4 threads");
            Assertions.assertEquals(twoThreads.getDataSize(), fourThreads.getDataSize(),
                    "Weak scaling data size should remain constant once native thread limit is reached");
        }
    }

    private static final class WeakScalingResultLookup {
        private final java.util.Map<Integer, BenchmarkRunner.WeakScalingResult> byRequested = new java.util.HashMap<>();

        WeakScalingResultLookup(java.util.List<BenchmarkRunner.WeakScalingResult> results) {
            for (BenchmarkRunner.WeakScalingResult result : results) {
                byRequested.put(result.getRequestedThreadCount(), result);
            }
        }

        BenchmarkRunner.WeakScalingResult byRequestedCount(int requested) {
            BenchmarkRunner.WeakScalingResult result = byRequested.get(requested);
            if (result == null) {
                throw new AssertionError("No weak scaling result for requested thread count: " + requested);
            }
            return result;
        }
    }

    private static double[] createDataset(int size) {
        double[] data = new double[size];
        for (int i = 0; i < size; i++) {
            data[i] = i + 1;
        }
        return data;
    }

    private static double plainSum(double[] data) {
        double sum = 0.0;
        for (double value : data) {
            sum += value;
        }
        return sum;
    }
}
