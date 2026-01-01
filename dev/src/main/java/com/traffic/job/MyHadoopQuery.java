package com.traffic.job;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.*;
import java.util.*;

/**
 * 随机抽样 + 条件查询示例
 * 功能：
 * 1. 从 HBase 表中随机抽取一个 road_seg_id（使用水塘抽样算法）。
 * 2. 查询该 road_seg_id 在指定时间范围内的数据。
 * 3. 将结果导出为 CSV 文件。
 */
public class MyHadoopQuery {

    public static void main(String[] args) throws Exception {

        // 1. 初始化 HBase 配置
        Configuration conf = HBaseConfiguration.create();

        Connection conn = null;
        Table table = null;

        try {
            // 2. 建立连接
            conn = ConnectionFactory.createConnection(conf);
            table = conn.getTable(TableName.valueOf("traffic_data"));

            // 3. 执行随机采样，获取一个存在的路段ID
            System.out.println("正在进行随机采样...");
            String roadId = reservoirSampleRoadId(table);

            if (roadId == null)
                throw new RuntimeException("未采样到 road_seg_id (表中可能无数据)");

            System.out.println("采样到的路段ID: " + roadId);

            // 4. 执行核心查询逻辑
            // 查询 2024-03-01 08:00 到 2024-03-02 08:00 之间的数据
            List<String> results =
                    query(table,
                          roadId,
                          "2024-03-01 08:00:00",
                          "2024-03-02 08:00:00");

            // 5. 将查询结果写入 CSV 文件
            File out = new File("./result/query.csv");
            out.getParentFile().mkdirs(); // 自动创建父目录

            try (PrintWriter w =
                         new PrintWriter(new FileWriter(out))) {
                // 写入表头
                w.println("time,road_seg_id,volume,speed");
                // 写入数据行
                for (String r : results) w.println(r);
            }
            System.out.println("查询完成，结果已写入: " + out.getAbsolutePath());

        } finally {
            // 6. 释放资源
            if (table != null) table.close();
            if (conn  != null) conn.close();
        }
    }

    /**
     * 使用水塘抽样算法（Reservoir Sampling）从表中随机选取一个 road_seg_id。
     * 这种算法可以在不知道总行数的情况下，对流式数据进行等概率抽样。
     */
    private static String reservoirSampleRoadId(Table table)
            throws IOException {

        Scan scan = new Scan();
        // 优化：使用 KeyOnlyFilter，只返回 RowKey，不读取 Value (Coloumn Family/Qualifier/Value)
        // 这样可以极大地减少网络 IO，因为我们只需要解析 RowKey 中的 ID
        scan.setFilter(new KeyOnlyFilter());
        scan.setCaching(500);

        ResultScanner scanner = null;
        try {
            scanner = table.getScanner(scan);
            Random rand = new Random();

            String selected = null;
            int count = 0;

            for (Result r : scanner) {
                // 解析 RowKey: 假设格式为 "时间|盐值|路段ID"
                String[] parts = Bytes.toString(r.getRow()).split("\\|");
                if (parts.length < 3) continue;

                count++;
                // 水塘抽样核心逻辑：
                // 对于第 N 个元素，以 1/N 的概率选择它替换当前结果。
                // 当循环结束时，所有元素被选中的概率都是相等的 (1/总数)。
                if (rand.nextInt(count) == 0) {
                    selected = parts[2]; // 更新选中的路段ID
                }
            }
            return selected;
        } finally {
            if (scanner != null) scanner.close();
        }
    }

    /**
     * 查询指定路段在特定时间范围内的数据
     *
     * @param table HBase 表对象
     * @param roadId 目标路段ID
     * @param start 开始时间字符串 (yyyy-MM-dd HH:mm:ss)
     * @param end 结束时间字符串 (yyyy-MM-dd HH:mm:ss)
     */
    private static List<String> query(
            Table table,
            String roadId,
            String start,
            String end)
            throws IOException {

        List<String> res = new ArrayList<>();
        Scan scan = new Scan();
        scan.setCaching(500);

        // 注意：此处代码演示的是客户端过滤（Client-side Filtering）。
        // 在生产环境中，为了性能，建议结合 FilterList (如 RowFilter 或 PrefixFilter)
        // 或者设计更适合查询的 RowKey 结构（例如将 ID 放在时间前面），以利用服务端过滤。
        ResultScanner scanner = null;
        try {
            scanner = table.getScanner(scan);
            for (Result r : scanner) {
                // 1. 解析 RowKey
                String[] parts = Bytes.toString(r.getRow()).split("\\|");
                if (parts.length < 3) continue;

                // 2. 过滤路段 ID (不匹配则跳过)
                if (!parts[2].equals(roadId)) continue;

                // 3. 过滤时间范围
                String time = parts[0];
                if (time.compareTo(start) < 0 ||
                    time.compareTo(end)   > 0) continue;

                // 4. 提取列数据 (volume 和 speed)
                String vol =
                        Bytes.toString(r.getValue(
                                Bytes.toBytes("info"),
                                Bytes.toBytes("volume")));
                String spd =
                        Bytes.toString(r.getValue(
                                Bytes.toBytes("info"),
                                Bytes.toBytes("speed")));

                // 5. 格式化结果并添加到列表
                res.add(time + "," + roadId + "," + vol + "," + spd);
            }
            return res;
        } finally {
            if (scanner != null) scanner.close();
        }
    }
}
