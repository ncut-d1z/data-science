package com.traffic.job;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 15 分钟窗口聚合任务
 * 功能：
 * 1. 从 HBase 表 'traffic_data' 读取原始交通流数据。
 * 2. 按照 15 分钟为一个时间窗口，对同一路段的数据进行聚合（累计车流量、计算平均车速）。
 * 3. 将聚合结果写回 HBase 表 'traffic_agg_15min'。
 * 4. 同时将结果导出到本地 CSV 文件 './result/agg.csv'。
 */
public class MyHadoopAggregate {

    /**
     * 内部类：用于在内存中暂存聚合结果的数据结构。
     * 对应一个路段(roadSegId)在一个时间窗口(window)内的统计数据。
     */
    static class AggregatedData {
        String roadSegId;   // 路段ID
        String window;      // 时间窗口字符串 (例如 "2024-03-01 08:00:00")
        double sumVolume = 0; // 总车流量
        double sumSpeed  = 0; // 速度总和（用于计算平均值）
        int count = 0;        // 记录条数

        AggregatedData(String roadSegId, String window) {
            this.roadSegId = roadSegId;
            this.window = window;
        }

        /**
         * 累加新的一条记录数据
         */
        void add(double volume, double speed) {
            sumVolume += volume;
            sumSpeed  += speed;
            count++;
        }

        /**
         * 计算当前窗口内的平均速度
         */
        double avgSpeed() {
            return count == 0 ? 0 : sumSpeed / count;
        }
    }

    public static void main(String[] args) throws Exception {

        // 1. 创建 HBase 配置
        Configuration conf = HBaseConfiguration.create();

        // 定义必要的资源对象，方便在 finally 中关闭
        Connection conn = null;
        Table table = null;      // 来源表
        Table aggTable = null;   // 目标表
        ResultScanner scanner = null;
        PrintWriter csvWriter = null;

        try {
            // 2. 建立连接并获取表对象
            conn = ConnectionFactory.createConnection(conf);
            table = conn.getTable(TableName.valueOf("traffic_data"));
            aggTable = conn.getTable(TableName.valueOf("traffic_agg_15min"));

            // 3. 准备 CSV 输出文件
            File out = new File("./result/agg.csv");
            out.getParentFile().mkdirs(); // 自动创建父目录
            csvWriter = new PrintWriter(new FileWriter(out));
            // 写入 CSV 表头
            csvWriter.println("road_seg_id,time_window,sum_volume,avg_speed,count");

            // 4. 配置 HBase 扫描器 (Scan)
            Scan scan = new Scan();
            scan.setCaching(500); // 性能优化：每次 RPC 请求预取 500 行数据，减少网络交互次数
            scan.setBatch(100);   // 性能优化：限制每次返回的列数（防止单行数据过大）

            // 获取结果扫描器
            scanner = table.getScanner(scan);

            // 5. 核心聚合逻辑
            // 使用 Map 存储当前时间窗口内的各路段数据
            // Key: roadSegId, Value: AggregatedData对象
            Map<String, AggregatedData> windowAgg = new HashMap<>();

            // 用于记录当前正在处理的时间窗口
            String currentWindow = null;

            // 遍历扫描到的每一行数据
            for (Result r : scanner) {
                // 解析 RowKey: 假设格式为 "时间|盐值|路段ID" (例如: 2024-03-01T08:05:00|1|R001)
                String[] parts = Bytes.toString(r.getRow()).split("\\|");
                if (parts.length < 3) continue; // 数据格式错误则跳过

                String timeStr = parts[0];
                String roadId  = parts[2];
                // 将原始时间转换为 15 分钟向下取整的时间窗口
                String window  = to15MinWindow(timeStr);

                // --- 窗口切换检测逻辑 ---
                // 因为 RowKey 是以时间开头的，所以数据大致是按时间排序的。
                // 当计算出的 window 与 currentWindow 不一致时，说明进入了下一个时间段。
                // 此时需要将上一个时间段内存中的聚合结果写入数据库(Flush)。
                if (currentWindow != null && !window.equals(currentWindow)) {
                    flushWindow(windowAgg, aggTable, csvWriter);
                    windowAgg.clear(); // 清空内存，准备统计下一个窗口
                }
                currentWindow = window;

                // 获取列数据 (列族 info)
                byte[] v = r.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume"));
                byte[] s = r.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed"));
                if (v == null || s == null) continue;

                // 解析数值
                double volume = Double.parseDouble(Bytes.toString(v));
                double speed  = Double.parseDouble(Bytes.toString(s));

                // 将数据累加到当前路段的统计对象中
                windowAgg
                    .computeIfAbsent(roadId,
                        k -> new AggregatedData(roadId, window))
                    .add(volume, speed);
            }

            // 6. 循环结束后，不要忘记将最后一个窗口的数据写入
            flushWindow(windowAgg, aggTable, csvWriter);

        } finally {
            // 7. 资源清理
            if (scanner   != null) scanner.close();
            if (csvWriter != null) csvWriter.close();
            if (table     != null) table.close();
            if (aggTable  != null) aggTable.close();
            if (conn      != null) conn.close();
        }
    }

    /**
     * 将内存中的聚合结果批量写入 HBase 和 CSV 文件
     *
     * @param data      内存中的聚合数据 Map
     * @param aggTable  HBase 目标表连接
     * @param csvWriter CSV 文件写入流
     */
    private static void flushWindow(
            Map<String, AggregatedData> data,
            Table aggTable,
            PrintWriter csvWriter) throws IOException {

        List<Put> puts = new ArrayList<>();

        for (AggregatedData d : data.values()) {
            // 生成新的 RowKey: 路段ID|时间窗口 (方便按路段查询历史趋势)
            String rowKey = d.roadSegId + "|" + d.window;
            Put put = new Put(Bytes.toBytes(rowKey));

            // 设置列数据
            put.addColumn(Bytes.toBytes("info"),
                          Bytes.toBytes("sum_volume"),
                          Bytes.toBytes(String.valueOf(d.sumVolume)));
            put.addColumn(Bytes.toBytes("info"),
                          Bytes.toBytes("avg_speed"),
                          Bytes.toBytes(String.valueOf(d.avgSpeed()))); // 写入计算后的平均速度
            put.addColumn(Bytes.toBytes("info"),
                          Bytes.toBytes("count"),
                          Bytes.toBytes(String.valueOf(d.count)));

            puts.add(put);

            // 同时写入 CSV
            csvWriter.println(
                    d.roadSegId + "," +
                    d.window + "," +
                    d.sumVolume + "," +
                    d.avgSpeed() + "," +
                    d.count
            );
        }

        // 批量提交 Put 请求到 HBase，提高写入效率
        if (!puts.isEmpty()) aggTable.put(puts);
        // 刷新 CSV 流，确保数据写入磁盘
        csvWriter.flush();
    }

    /**
     * 工具方法：将任意时间字符串向下取整到最近的 15 分钟刻度
     * 例如：08:05 -> 08:00, 08:20 -> 08:15
     */
    private static String to15MinWindow(String timeStr) {
        try {
            // 输入格式对应原始数据的 RowKey 时间格式 (ISO 8601 变体)
            SimpleDateFormat in =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            Calendar c = Calendar.getInstance();
            c.setTime(in.parse(timeStr));

            // 获取分钟数
            int m = c.get(Calendar.MINUTE);
            // 核心算法：(分钟 / 15) * 15 实现向下取整
            c.set(Calendar.MINUTE, (m / 15) * 15);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);

            // 输出格式化为标准 SQL 时间格式
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(c.getTime());
        } catch (Exception e) {
            // 如果解析失败，原样返回，避免程序崩溃
            return timeStr;
        }
    }
}
