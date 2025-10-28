package com.benchmark.teaclave.enclave;

import com.benchmark.teaclave.common.Ack;
import com.benchmark.teaclave.common.Service;
import com.benchmark.enclave.dp.BinaryAggregationTree;
import com.google.auto.service.AutoService;

@AutoService(Service.class)
public class ServiceImpl implements Service {

    private int id_counter = 0;

    @Override
    public String sayHelloWorld() {
        return "Hello World";
    }

    @Override
    public Ack pushValue(int value) {
      Ack resp = new Ack(id_counter++,value);
      return resp;
    }

    @Override
    public double runBinaryAggregation(double[] values, double sigma) {
      if (values == null) {
        throw new IllegalArgumentException("values must not be null");
      }
      if (values.length == 0) {
        return 0.0;
      }
      if (sigma < 0.0) {
        throw new IllegalArgumentException("sigma must be non-negative");
      }
      return BinaryAggregationTree.hierarchicalPerturbationEnc(values, sigma);
    }
}
