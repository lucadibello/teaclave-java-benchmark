package com.benchmark.enclave.dp;

import java.util.Random;

/**
 * Binary aggregation tree that injects Gaussian noise per node and keeps track of
 * the private sum while values are inserted sequentially.
 */
public final class BinaryAggregationTree {

    private final double[] tree;
    private final int height;
    private final int numLeaves;
    private final int maxValues;
    private double curPrivateSum;

    public BinaryAggregationTree(int numberOfValues, double sigma) {
        if (numberOfValues <= 0) {
            throw new IllegalArgumentException("numberOfValues must be positive");
        }
        this.height = computeHeight(numberOfValues);
        this.numLeaves = 1 << this.height;
        this.maxValues = numberOfValues;
        this.tree = initialiseTree(sigma);
        this.curPrivateSum = 0.0;
    }

    public double getTotalSum() {
        return curPrivateSum;
    }

    public double addToTree(int index, double value) {
        if (index < 0 || index >= maxValues) {
            throw new IllegalArgumentException("index out of range for tree: " + index);
        }

        int nodeIndex = numLeaves - 1 + index;

        while (nodeIndex > 0) {
            tree[nodeIndex] += value;
            nodeIndex = (nodeIndex - 1) / 2;
        }
        tree[0] += value;

        double privateSum = 0.0;
        String indexBinary = toBinaryString(index + 1, height + 1);
        String pathBinary = toBinaryString(index, height);

        int currentIndex = 0;
        for (int level = 0; level < height + 1; level++) {
            char vertexBit = indexBinary.charAt(level);
            if (vertexBit == '1') {
                int leftSibling = (currentIndex % 2 == 0) ? currentIndex - 1 : currentIndex;
                if (currentIndex == 0) {
                    leftSibling = 0;
                }
                privateSum += tree[leftSibling];
            }
            if (level < height) {
                char pathBit = pathBinary.charAt(level);
                int leftChild = 2 * currentIndex + 1;
                currentIndex = (pathBit == '0') ? leftChild : leftChild + 1;
            }
        }

        curPrivateSum = privateSum;
        return privateSum;
    }

    public static double hierarchicalPerturbationEnc(double[] data, double sigma) {
        BinaryAggregationTree tree = new BinaryAggregationTree(data.length, sigma);
        for (int i = 0; i < data.length; i++) {
            tree.addToTree(i, data[i]);
        }
        return tree.getTotalSum();
    }

    private double[] initialiseTree(double sigma) {
        int treeSize = 2 * numLeaves - 1;
        double[] values = new double[treeSize];
        Random random = new Random();
        for (int i = 0; i < treeSize; i++) {
            values[i] = random.nextGaussian() * sigma;
        }
        return values;
    }

    private static int computeHeight(int n) {
        int height = 0;
        int capacity = 1;
        while (capacity < n) {
            capacity <<= 1;
            height++;
        }
        return height;
    }

    private static String toBinaryString(int value, int width) {
        if (width <= 0) {
            return "";
        }
        String binary = Integer.toBinaryString(value);
        if (binary.length() >= width) {
            return binary.substring(binary.length() - width);
        }
        StringBuilder builder = new StringBuilder(width);
        for (int i = binary.length(); i < width; i++) {
            builder.append('0');
        }
        builder.append(binary);
        return builder.toString();
    }
}
