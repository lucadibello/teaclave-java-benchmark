package com.benchmark.teaclave.enclave;

import com.benchmark.teaclave.common.Service;
import com.benchmark.teaclave.common.Service;
import com.benchmark.teaclave.enclave.dp.BinaryAggregationTree;
import com.google.auto.service.AutoService;

@AutoService(Service.class)
public class ServiceImpl implements Service {

    private int id_counter = 0;
    private BinaryAggregationTree aggregationTree;
    private int aggregationCapacity;
    private int aggregationIndex;
    private Double lastPrivateSum;

    @Override
    public synchronized void initBinaryAggregation(int n, double sigma) {
      if (n <= 0) {
        throw new IllegalArgumentException("n must be positive");
      }
      if (sigma < 0.0) {
        throw new IllegalArgumentException("sigma must be non-negative");
      }
      aggregationTree = new BinaryAggregationTree(n, sigma);
      aggregationCapacity = n;
      aggregationIndex = 0;
      lastPrivateSum = 0.0;
    }

    @Override
    public synchronized double addToBinaryAggregation(double value) {
      ensureAggregationInitialised();
      if (aggregationIndex >= aggregationCapacity) {
        throw new IllegalStateException("Binary aggregation tree capacity exceeded");
      }
      lastPrivateSum = aggregationTree.addToTree(aggregationIndex, value);
      aggregationIndex++;
      return lastPrivateSum;
    }

    @Override
    public synchronized double getBinaryAggregationSum() {
      ensureAggregationInitialised();
      return lastPrivateSum;
    }

    private void ensureAggregationInitialised() {
      if (aggregationTree == null) {
        throw new IllegalStateException("Binary aggregation tree not initialised");
      }
    }
}
