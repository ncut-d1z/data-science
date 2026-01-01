package com.traffic.analysis;

/**
 * 在线 Pearson 相关系数累加器
 *
 * 特点：
 * - 不保存样本
 * - O(1) 内存
 * - 适合大规模数据
 */
public class MyHadoopCorrAccumulator {

    private long n = 0;
    private double sumX = 0;
    private double sumY = 0;
    private double sumXX = 0;
    private double sumYY = 0;
    private double sumXY = 0;

    public void add(double x, double y) {
        n++;
        sumX  += x;
        sumY  += y;
        sumXX += x * x;
        sumYY += y * y;
        sumXY += x * y;
    }

    public long getCount() {
        return n;
    }

    public double correlation() {
        if (n < 2) {
            return 0.0;
        }
        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt(
                (n * sumXX - sumX * sumX) *
                (n * sumYY - sumY * sumY)
        );
        if (denominator == 0) {
            return 0.0;
        }
        return numerator / denominator;
    }
}
