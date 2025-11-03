package com.benchmark.teaclave.host;

import com.benchmark.teaclave.common.Service;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;

import java.util.Arrays;
import java.util.Iterator;

public final class Main {

    private static final String ENV_ENCLAVE_TYPE = "TEACLAVE_BENCH_ENCLAVE_TYPE";
    private static final String ENV_SIGMA = "TEACLAVE_BENCH_SIGMA";
    private static final String ENV_WEAK_THREADS = "TEACLAVE_BENCH_WEAK_SCALES";
    private static final String ENV_STRONG_THREADS = "TEACLAVE_BENCH_STRONG_SCALES";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        EnclaveType enclaveType = resolveEnclaveType(args);
        System.out.println("Using enclave type: " + enclaveType.name());
        double sigma = resolveSigma();

        // resolve thread counts
        int[] weakThreadCounts = resolveThreadArray(ENV_WEAK_THREADS, new int[]{1, 2, 4, 8, 16, 32});
        int[] strongThreadCounts = resolveThreadArray(ENV_STRONG_THREADS, new int[]{1, 2, 4, 8, 16, 32});

        // compute baseline threads for workload preparation
        int baselineThreads = Math.max(1, Math.min(
                Arrays.stream(weakThreadCounts).min().orElse(Integer.MAX_VALUE),
                Arrays.stream(strongThreadCounts).min().orElse(Integer.MAX_VALUE)
            ));

        Enclave enclave = EnclaveFactory.create(enclaveType);
        Iterator<Service> services = enclave.load(Service.class);
        if (!services.hasNext()) {
            throw new IllegalStateException("Unable to locate Service implementation inside enclave");
        }
        Service service = services.next();

        try {
            // load workload settings from environment
            WorkloadSettings workloadSettings = WorkloadSettings.fromEnvironment(sigma);
            // print settings
            System.out.println("Initial workload: " + workloadSettings.getDataSize());

            // create benchmark runner
            BenchmarkRunner runner = new BenchmarkRunner(service);

            // create workload based on settings
            Workload workload = runner.prepareWorkload(workloadSettings, baselineThreads);
            System.out.println("Prepared workload on baseline threads: " + baselineThreads);

            // run both scaling benchmarks
            var weakResults = runner.runWeakScaling(workload, weakThreadCounts);
            var strongResults = runner.runStrongScaling(workload, strongThreadCounts);

            // create summary
            BenchmarkSummary summary = new BenchmarkSummary(
                workloadSettings,
                enclaveType.name(),
                workload,
                weakThreadCounts,
                weakResults,
                strongThreadCounts,
                strongResults,
                baselineThreads
            );

            // print it
            System.out.println("== Benchmark Summary ==");
            System.out.println(summary.toPrettyString());
        } finally {
            try {
                enclave.destroy();
            } catch (Exception e) {
                System.err.println("Failed to destroy enclave: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }

    private static EnclaveType resolveEnclaveType(String[] args) {
        if (args != null && args.length > 0) {
            return EnclaveType.valueOf(args[0]);
        }
        String fromEnv = System.getenv(ENV_ENCLAVE_TYPE);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return EnclaveType.valueOf(fromEnv.trim());
        }
        return EnclaveType.MOCK_IN_JVM;
    }

    private static double resolveSigma() {
        String raw = System.getenv(ENV_SIGMA);
        if (raw == null || raw.isEmpty()) {
            return 0.5;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Unable to parse sigma from " + ENV_SIGMA + "=" + raw, nfe);
        }
    }

    private static int[] resolveThreadArray(String envKey, int[] defaults) {
        String raw = System.getenv(envKey);
        if (raw == null || raw.isEmpty()) {
            return defaults;
        }
        String[] tokens = raw.split(",");
        int[] values = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty thread token in " + envKey + "=" + raw);
            }
            try {
                values[i] = Integer.parseInt(token);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid integer '" + token + "' in " + envKey + "=" + raw, nfe);
            }
            if (values[i] <= 0) {
                throw new IllegalArgumentException("Thread counts must be positive: " + Arrays.toString(values));
            }
        }
        return values;
    }
}
