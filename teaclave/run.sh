#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

if [[ -f "${ENV_FILE}" ]]; then
  # Export variables defined in .env so they reach Maven and the benchmark.
  set -a
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  set +a
fi

ENABLE_OCCLUM="${ENABLE_OCCLUM:-false}"

mvn -Pnative clean package -DskipTests

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
else
  JAVA_BIN="$(command -v java)"
fi

JAVA_CMD=(
  "${JAVA_BIN}"
  -cp "host/target/host-1.0-SNAPSHOT-jar-with-dependencies.jar:enclave/target/enclave-1.0-SNAPSHOT-jar-with-dependencies.jar"
  com.benchmark.teaclave.host.Main
)

# Whitelist the benchmark-related environment variables so sudo will see them.
BENCH_ENV_VARS=(
  TEACLAVE_BENCH_ENCLAVE_TYPE
  TEACLAVE_BENCH_SIGMA
  TEACLAVE_BENCH_WEAK_SCALES
  TEACLAVE_BENCH_STRONG_SCALES
  TEACLAVE_BENCH_INITIAL_SIZE
  TEACLAVE_BENCH_MAX_SIZE
  TEACLAVE_BENCH_TARGET_MS
  TEACLAVE_BENCH_WARMUP
  TEACLAVE_BENCH_MEASURE
  TEACLAVE_BENCH_NATIVE_PARALLELISM
  ENABLE_OCCLUM
)

BENCH_ENV_ARGS=()
for var in "${BENCH_ENV_VARS[@]}"; do
  if [[ -n "${!var:-}" ]]; then
    BENCH_ENV_ARGS+=("${var}=${!var}")
  fi
done

if [[ "${ENABLE_OCCLUM}" == "true" ]]; then
  BENCH_ENV_ARGS+=("OCCLUM_RELEASE_ENCLAVE=${ENABLE_OCCLUM}")
fi

# Run with sudo for SGX device access while preserving the curated environment.
sudo env "${BENCH_ENV_ARGS[@]}" "${JAVA_CMD[@]}"
