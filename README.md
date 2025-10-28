# Teaclave Java Benchmark

## Overview

This project demonstrates an end-to-end Apache Teaclave Java SDK application that benchmarks a differentially private binary aggregation tree executed inside an enclave. The repository is organised as the standard Teaclave multi-module Maven build:

- `common` – Declares enclave service interfaces and shared DTOs.
- `enclave` – Implements the enclave-side logic, including the binary aggregation tree and OCALL/ECALL wiring.
- `host` – Hosts the enclave, drives the benchmark workflow, and reports summarised metrics that are ready to plot.

The default build targets the Teaclave SDK mock enclave for rapid iteration, and can be switched to hardware-backed enclaves without changing the benchmark code.

The repository ships with a pre-configured VS Code devcontainer (`.devcontainer/`) plus a `go-task` automation (`taskfile.yml`). Opening the project in VS Code or running `task devcontainer` provisions every dependency needed to build and run the benchmark from a clean machine.

### go-task entrypoints

If you are not using VS Code you can still bootstrap the containerised environment via `go-task`. The following helpers are defined in `taskfile.yml` (run `task -l` to see them on your machine):

| Task | Description |
|------|-------------|
| `task devcontainer` | Build, start, and attach to the devcontainer (composite of the tasks below). |
| `task devcontainer-build` | Build the container image for this workspace. |
| `task devcontainer-up` | Start or reuse the devcontainer without attaching. |
| `task devcontainer-attach` | Exec into the running devcontainer (drops you into a shell). |
| `task devcontainer-down` | Stop and remove the container plus volumes. |
| `task devcontainer-recreate` | Tear down and rebuild the devcontainer from scratch. |

## Differential Privacy Aggregation

The enclave module adapts the `BinaryAggregationTreeBase` algorithm to a Teaclave friendly implementation:

- Primitive arrays are used instead of Java collections to reduce allocation overhead and avoid static mutable state.
- Each node in the tree receives Gaussian noise sampled with user-controlled variance `sigma`.
- The APIs `initBinaryAggregation(int n, double sigma)` and `addToBinaryAggregation(double value)` mirror the original `BinaryAggregationTreeBase`: initialise once with the desired capacity (e.g. *n* = 10 000) and stream values sequentially from the host into the enclave. `getBinaryAggregationSum()` exposes the running private sum for inspection.

The host code invokes these APIs repeatedly during calibration and scaling measurements to capture realistic enclave call costs.

## Benchmark Workflow

The benchmark is driven by `com.benchmark.teaclave.host.Main`, which follows three stages:

1. **Calibration** – Grow the dataset until the average latency reaches the target budget (`TEACLAVE_BENCH_TARGET_US`, default 500 µs). This determines a single `dataSize` used as the baseline workload.
2. **Weak scaling** – Re-run the aggregation with increasing scale factors (default `1,2,4,8`), multiplying the calibrated dataset size each time. This measures how the algorithm behaves as the problem size grows with constant per-worker workload.
3. **Strong scaling** – Split a larger dataset into several partitions (default `1,2,4,8`) and process each partition sequentially to approximate scaling across workers sharing a fixed combined workload.

After all runs complete, a JSON summary is printed to stdout:

```
== Benchmark Summary ==
{
  "calibration": {
    "dataSize": 512,
    "sigma": 0.500000,
    "iterations": 5,
    "avgTimeMicros": 871.74,
    "attempts": 2
  },
  "weakScaling": [
    {"scaleFactor": 1, "dataSize": 512, "iterations": 5, "avgTimeMicros": 504.25},
    {"scaleFactor": 2, "dataSize": 1024, "iterations": 5, "avgTimeMicros": 891.93}
  ],
  "strongScaling": [
    {"partitions": 1, "totalSize": 4096, "minPartitionSize": 512, "maxPartitionSize": 512, "iterations": 5, "avgTimeMicros": 2275.56},
    {"partitions": 2, "totalSize": 4096, "minPartitionSize": 512, "maxPartitionSize": 1024, "iterations": 5, "avgTimeMicros": 2043.22}
  ]
}
```

Each `avgTimeMicros` value captures the average wall-clock time (including enclave entry) required to initialise the tree and stream a full batch of values through `addToBinaryAggregation`. These metrics can be ingested directly into plotting tools for weak and strong scaling curves.

## Configuration

Fine-tune the benchmark by setting environment variables before running the host application:

| Variable | Default | Purpose |
|----------|---------|---------|
| `TEACLAVE_BENCH_ENCLAVE_TYPE` | `MOCK_IN_JVM` | Selects the enclave flavour (`MOCK_IN_JVM`, `MOCK_IN_SVM`, `TEE_SDK`). |
| `TEACLAVE_BENCH_SIGMA` | `0.5` | Standard deviation of the Gaussian noise injected per tree node. |
| `TEACLAVE_BENCH_WEAK_SCALES` | `1,2,4,8,16` | Comma-separated scale factors for weak scaling. |
| `TEACLAVE_BENCH_STRONG_SCALES` | `1,2,4,8,16` | Comma-separated partition counts for strong scaling. |
| `TEACLAVE_BENCH_INITIAL_SIZE` | `256` | Dataset size used for the first calibration attempt. |
| `TEACLAVE_BENCH_MAX_SIZE` | `65536` | Upper bound for calibration growth. |
| `TEACLAVE_BENCH_TARGET_US` | `500` | Minimum average latency (µs) targeted during calibration. Combine with `TEACLAVE_BENCH_INITIAL_SIZE`/`TEACLAVE_BENCH_MAX_SIZE` to lock the workload to a fixed *n* (e.g. 10 000) when reproducing advisor-suggested measurements. |
| `TEACLAVE_BENCH_GROWTH_FACTOR` | `2` | Multiplier applied when calibration needs a larger workload. |
| `TEACLAVE_BENCH_WARMUP` | `3` | Warm-up iterations per measurement pass. |
| `TEACLAVE_BENCH_MEASURE` | `5` | Measurement iterations per pass. |

All timings are recorded in microseconds and derive from `System.nanoTime()`. The benchmark perturbs input arrays slightly between iterations to avoid JVM optimisations based on constant data.

## Building

1. Install a JDK (11+) and Maven 3.6+.
2. From the project root, build all modules:

   ```
   mvn package
   ```

   Running the tests is optional but recommended:

   ```
   mvn -pl host test
   ```

   The host tests exercise calibration and scaling using the mock enclave; if the service implementation is not available, the tests are skipped automatically.

## Running the Benchmark

After packaging, execute the host app with the enclave and host shaded jars on the classpath:

```
java \
  -cp host/target/host-1.0-SNAPSHOT-jar-with-dependencies.jar:enclave/target/enclave-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.benchmark.teaclave.host.Main
```

To switch enclave types or tweak parameters:

```
TEACLAVE_BENCH_ENCLAVE_TYPE=TEE_SDK \
TEACLAVE_BENCH_SIGMA=0.25 \
TEACLAVE_BENCH_WEAK_SCALES=1,2,4,8,16 \
TEACLAVE_BENCH_STRONG_SCALES=1,2,4,8 \
java -cp ... com.benchmark.teaclave.host.Main
```

The final summary printed to stdout contains all metrics needed for plotting or downstream reporting.

## Collecting Metrics

Redirect the program output to capture calibration, weak scaling, and strong scaling results in one artefact:

```
java -cp ... com.benchmark.teaclave.host.Main > benchmark-results.json
```

The resulting file is line-oriented JSON that can be parsed by scripts or ingested into tooling such as Python/pandas, R, or gnuplot to generate scaling charts.
