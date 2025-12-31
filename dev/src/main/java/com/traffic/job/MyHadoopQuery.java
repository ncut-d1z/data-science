package com.traffic.job;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.filter.*;
import java.io.*;
import java.util.*;
import com.traffic.*;

public class MyHadoopQuery {

    public static void main(String[] args) throws Exception {
        Configuration config = HBaseConfiguration.create();
        Connection connection = ConnectionFactory.createConnection(config);
        Table table = connection.getTable(TableName.valueOf("traffic_data"));

        // 获取所有有效的 road_seg_id（去重）
        List<String> roadIds = getAllRoadIds(table);

        if (roadIds.isEmpty()) {
            throw new RuntimeException("未从 HBase 中读取到任何有效的 road_seg_id");
        }

        // 随机抽样一个 road_seg_id
        Random random = new Random();
        String sampleRoadId = roadIds.get(random.nextInt(roadIds.size()));
        System.out.println("[INFO] 随机抽样得到的 road_seg_id: " + sampleRoadId);

        // 给定监测点、时间段，查询流量数据
        List<String> results = queryTrafficData(table, sampleRoadId, "2024-03-01 08:00:00", "2024-03-02 08:00:00");

        // 将结果写入CSV文件
        writeResultsToCSV(results, "./result/query.csv");

        table.close();
        connection.close();
    }

    private static List<String> getAllRoadIds(Table table) throws IOException {
        List<String> roadIds = new ArrayList<>();
        Scan scan = new Scan();
        scan.setCaching(1000); // 设置缓存大小

        ResultScanner scanner = table.getScanner(scan);
        for (Result result : scanner) {
            String rowKey = Bytes.toString(result.getRow());
            // 从rowkey中提取road_seg_id (格式: time|salt|road_seg_id)
            String[] parts = rowKey.split("\\|");
            if (parts.length >= 3) {
                String roadId = parts[2];
                if (!roadIds.contains(roadId)) {
                    roadIds.add(roadId);
                }
            }
        }
        scanner.close();
        return roadIds;
    }

    private static List<String> queryTrafficData(Table table, String roadId, String startTime, String endTime) throws IOException {
        List<String> results = new ArrayList<>();

        // 使用前缀过滤器获取特定roadId的数据
        FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL);
        filterList.addFilter(new PrefixFilter(Bytes.toBytes(startTime.substring(0, 10)))); // 按日期前缀过滤

        Scan scan = new Scan();
        scan.setFilter(filterList);
        scan.setCaching(1000);

        ResultScanner scanner = table.getScanner(scan);
        for (Result result : scanner) {
            String rowKey = Bytes.toString(result.getRow());
            String[] parts = rowKey.split("\\|");
            if (parts.length >= 3 && parts[2].equals(roadId)) {
                String volume = Bytes.toString(result.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume")));
                String speed = Bytes.toString(result.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed")));
                String time = parts[0]; // 从rowkey中提取时间

                // 检查时间是否在范围内
                if (time.compareTo(startTime) >= 0 && time.compareTo(endTime) <= 0) {
                    results.add(time + "," + roadId + "," + volume + "," + speed);
                }
            }
        }
        scanner.close();
        return results;
    }

    private static void writeResultsToCSV(List<String> results, String filename) throws IOException {
        File file = new File(filename);
        file.getParentFile().mkdirs(); // 创建目录

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("time,road_seg_id,volume,speed"); // CSV表头
            for (String result : results) {
                writer.println(result);
            }
        }
    }
}
