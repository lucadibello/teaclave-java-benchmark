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
        BenchmarkRunner runner = new BenchmarkRunner(service, new Random(456L));
        BenchmarkRunner.CalibrationSettings settings =
                new BenchmarkRunner.CalibrationSettings(32, 1024, 5.0, 2, 1, 3, 0.25);
        int[] weakThreads = new int[]{1, 2, 4};
        int[] strongThreads = new int[]{1, 2, 4};
        BenchmarkRunner.CalibratedWorkload workload = runner.calibrate(settings, 1);
        var weakResults = runner.runWeakScaling(workload, weakThreads, 3);
        var strongResults = runner.runStrongScaling(workload, strongThreads, 3);
        BenchmarkRunner.BenchmarkSummary summary =
                new BenchmarkRunner.BenchmarkSummary(workload, weakResults, strongResults);

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

    @Test
    void calibrationProducesWorkload() {
        BenchmarkRunner runner = new BenchmarkRunner(service, new Random(123L));
        BenchmarkRunner.CalibrationSettings settings =
                new BenchmarkRunner.CalibrationSettings(64, 4096, 10.0, 2, 1, 2, 0.0);
        BenchmarkRunner.CalibratedWorkload workload = runner.calibrate(settings, 1);
        Assertions.assertTrue(workload.getDataSize() >= 64, "Calibrated data size should grow from initial guess");
        Assertions.assertTrue(workload.getAverageMillis() >= 0.0, "Average millis should be non-negative");
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
