package com.traffic.job;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.filter.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.traffic.*;

public class MyHadoopAnalysis {

    // 用于存储相关性分析结果的类
    static class CorrelationResult {
        String siteA;
        String siteB;
        double volumeCorrelation;
        double speedCorrelation;

        public CorrelationResult(String siteA, String siteB, double volumeCorrelation, double speedCorrelation) {
            this.siteA = siteA;
            this.siteB = siteB;
            this.volumeCorrelation = volumeCorrelation;
            this.speedCorrelation = speedCorrelation;
        }
    }

    // 用于存储时间点数据的类
    static class TimePointData {
        String date;
        String time;
        double volume;
        double speed;

        public TimePointData(String date, String time, double volume, double speed) {
            this.date = date;
            this.time = time;
            this.volume = volume;
            this.speed = speed;
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration config = HBaseConfiguration.create();
        Connection connection = ConnectionFactory.createConnection(config);
        Table table = connection.getTable(TableName.valueOf("traffic_data"));

        // 获取所有数据并按站点和日期分组
        Map<String, Map<String, List<TimePointData>>> siteDateData = collectSiteDateData(table);

        // 计算站点间相关性
        List<CorrelationResult> correlationResults = calculateCrossSiteCorrelation(siteDateData);

        // 按相关性排序
        correlationResults.sort((a, b) -> Double.compare(b.volumeCorrelation, a.volumeCorrelation));

        // 写入CSV文件
        writeCorrelationToCSV(correlationResults, "./result/corr.csv");

        table.close();
        connection.close();
    }

    private static Map<String, Map<String, List<TimePointData>>> collectSiteDateData(Table table) throws IOException {
        Map<String, Map<String, List<TimePointData>>> siteDateData = new HashMap<>();

        Scan scan = new Scan();
        scan.setCaching(1000);

        ResultScanner scanner = table.getScanner(scan);
        for (Result result : scanner) {
            String rowKey = Bytes.toString(result.getRow());
            String[] parts = rowKey.split("\\|");
            if (parts.length >= 3) {
                String timeStr = parts[0];
                String roadId = parts[2];

                String volumeStr = Bytes.toString(result.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume")));
                String speedStr = Bytes.toString(result.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed")));

                try {
                    double volume = Double.parseDouble(volumeStr);
                    double speed = Double.parseDouble(speedStr);

                    // 提取日期
                    String date = timeStr.split("T")[0];
                    String time = timeStr.split("T")[1];

                    // 创建嵌套映射
                    siteDateData.computeIfAbsent(roadId, k -> new HashMap<>())
                               .computeIfAbsent(date, k -> new ArrayList<>())
                               .add(new TimePointData(date, time, volume, speed));
                } catch (NumberFormatException e) {
                    // 跳过无效数据
                }
            }
        }
        scanner.close();

        return siteDateData;
    }

    private static List<CorrelationResult> calculateCrossSiteCorrelation(Map<String, Map<String, List<TimePointData>>> siteDateData) {
        List<CorrelationResult> results = new ArrayList<>();
        List<String> siteIds = new ArrayList<>(siteDateData.keySet());

        // 计算站点对之间的相关性
        for (int i = 0; i < siteIds.size(); i++) {
            for (int j = i + 1; j < siteIds.size(); j++) {
                String siteA = siteIds.get(i);
                String siteB = siteIds.get(j);

                // 获取两个站点的共同日期数据
                Map<String, List<TimePointData>> siteAData = siteDateData.get(siteA);
                Map<String, List<TimePointData>> siteBData = siteDateData.get(siteB);

                // 找到共同的日期
                Set<String> commonDates = new HashSet<>(siteAData.keySet());
                commonDates.retainAll(siteBData.keySet());

                if (!commonDates.isEmpty()) {
                    // 收集共同日期的数据
                    List<Double> volumesA = new ArrayList<>();
                    List<Double> volumesB = new ArrayList<>();
                    List<Double> speedsA = new ArrayList<>();
                    List<Double> speedsB = new ArrayList<>();

                    for (String date : commonDates) {
                        List<TimePointData> timePointsA = siteAData.get(date);
                        List<TimePointData> timePointsB = siteBData.get(date);

                        // 按时间匹配数据点
                        Map<String, TimePointData> timeMapB = new HashMap<>();
                        for (TimePointData tp : timePointsB) {
                            timeMapB.put(tp.time, tp);
                        }

                        for (TimePointData tpA : timePointsA) {
                            TimePointData tpB = timeMapB.get(tpA.time);
                            if (tpB != null) {
                                volumesA.add(tpA.volume);
                                volumesB.add(tpB.volume);
                                speedsA.add(tpA.speed);
                                speedsB.add(tpB.speed);
                            }
                        }
                    }

                    // 计算相关系数
                    double volumeCorr = calculateCorrelation(volumesA, volumesB);
                    double speedCorr = calculateCorrelation(speedsA, speedsB);

                    results.add(new CorrelationResult(siteA, siteB, volumeCorr, speedCorr));
                }
            }
        }

        return results;
    }

    private static double calculateCorrelation(List<Double> x, List<Double> y) {
        if (x.size() != y.size() || x.size() < 2) {
            return 0.0;
        }

        // 计算平均值
        double meanX = x.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanY = y.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        // 计算协方差和标准差
        double covariance = 0.0;
        double varianceX = 0.0;
        double varianceY = 0.0;

        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }

        double stdDevX = Math.sqrt(varianceX);
        double stdDevY = Math.sqrt(varianceY);

        if (stdDevX == 0 || stdDevY == 0) {
            return 0.0;
        }

        return covariance / (stdDevX * stdDevY);
    }

    private static void writeCorrelationToCSV(List<CorrelationResult> correlationResults, String filename) throws IOException {
        File file = new File(filename);
        file.getParentFile().mkdirs(); // 创建目录

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("site_a,site_b,volume_corr,speed_corr"); // CSV表头
            for (CorrelationResult result : correlationResults) {
                writer.println(result.siteA + "," + result.siteB + "," +
                              result.volumeCorrelation + "," + result.speedCorrelation);
            }
        }
    }
}
