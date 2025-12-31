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

public class MyHadoopAggregate {

    // 用于存储15分钟聚合数据的类
    static class AggregatedData {
        String roadSegId;
        String timeWindow;
        double sumVolume;
        double avgSpeed;
        int count;

        public AggregatedData(String roadSegId, String timeWindow) {
            this.roadSegId = roadSegId;
            this.timeWindow = timeWindow;
            this.sumVolume = 0;
            this.avgSpeed = 0;
            this.count = 0;
        }

        public void addData(double volume, double speed) {
            this.sumVolume += volume;
            this.avgSpeed = (this.avgSpeed * this.count + speed) / (this.count + 1);
            this.count++;
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration config = HBaseConfiguration.create();
        Connection connection = ConnectionFactory.createConnection(config);
        Table table = connection.getTable(TableName.valueOf("traffic_data"));

        // 获取所有数据并进行15分钟聚合
        Map<String, AggregatedData> aggregatedResults = aggregateData(table);

        // 将聚合结果写入新的HBase表
        writeToHBaseAggregated(aggregatedResults, connection);

        // 同时写入CSV文件
        writeAggregatedToCSV(aggregatedResults, "./result/agg.csv");

        table.close();
        connection.close();
    }

    private static Map<String, AggregatedData> aggregateData(Table table) throws IOException {
        Map<String, AggregatedData> aggregatedData = new ConcurrentHashMap<>();

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

                    // 将时间转换为15分钟窗口
                    String timeWindow = getTimeWindow(timeStr, 15);
                    String key = roadId + "|" + timeWindow;

                    aggregatedData.computeIfAbsent(key, k -> new AggregatedData(roadId, timeWindow))
                                  .addData(volume, speed);
                } catch (NumberFormatException e) {
                    // 跳过无效数据
                }
            }
        }
        scanner.close();

        return aggregatedData;
    }

    private static String getTimeWindow(String timeStr, int minutes) {
        try {
            // 解析时间字符串
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            Date date = inputFormat.parse(timeStr);

            // 计算15分钟窗口
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            int minute = cal.get(Calendar.MINUTE);
            int windowStartMinute = (minute / minutes) * minutes;
            cal.set(Calendar.MINUTE, windowStartMinute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return outputFormat.format(cal.getTime());
        } catch (Exception e) {
            return timeStr; // 如果解析失败，返回原时间
        }
    }

    private static void writeToHBaseAggregated(Map<String, AggregatedData> aggregatedData, Connection connection) throws IOException {
        Table aggTable = connection.getTable(TableName.valueOf("traffic_agg_15min"));

        List<Put> puts = new ArrayList<>();
        for (Map.Entry<String, AggregatedData> entry : aggregatedData.entrySet()) {
            AggregatedData data = entry.getValue();
            String rowKey = data.roadSegId + "|" + data.timeWindow;

            Put put = new Put(Bytes.toBytes(rowKey));
            put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("sum_volume_15min"),
                         Bytes.toBytes(String.valueOf(data.sumVolume)));
            put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("avg_speed_15min"),
                         Bytes.toBytes(String.valueOf(data.avgSpeed)));
            put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("count"),
                         Bytes.toBytes(String.valueOf(data.count)));

            puts.add(put);
        }

        if (!puts.isEmpty()) {
            aggTable.put(puts);
        }

        aggTable.close();
    }

    private static void writeAggregatedToCSV(Map<String, AggregatedData> aggregatedData, String filename) throws IOException {
        File file = new File(filename);
        file.getParentFile().mkdirs(); // 创建目录

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("road_seg_id,time_window,sum_volume_15min,avg_speed_15min,count"); // CSV表头
            for (Map.Entry<String, AggregatedData> entry : aggregatedData.entrySet()) {
                AggregatedData data = entry.getValue();
                String[] parts = entry.getKey().split("\\|");
                String roadId = parts[0];
                String timeWindow = parts[1];

                writer.println(roadId + "," + timeWindow + "," +
                              data.sumVolume + "," + data.avgSpeed + "," + data.count);
            }
        }
    }
}
