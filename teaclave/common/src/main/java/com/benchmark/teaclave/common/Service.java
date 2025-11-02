package com.benchmark.teaclave.common;

import org.apache.teaclave.javasdk.common.annotations.EnclaveService;

@EnclaveService
public interface Service {
    void initBinaryAggregation(int n, double sigma);
    double addToBinaryAggregation(double value);
    double getBinaryAggregationSum();
}
