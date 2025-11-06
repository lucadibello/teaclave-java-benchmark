#!/bin/bash

# Run host
TEACLAVE_BENCH_ENCLAVE_TYPE=TEE_SDK java -cp "host/target/host-1.0-SNAPSHOT-jar-with-dependencies.jar:enclave/target/enclave-1.0-SNAPSHOT-jar-with-dependencies.jar" com.benchmark.teaclave.host.Main
