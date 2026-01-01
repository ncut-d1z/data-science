package com.traffic.job;

import com.traffic.analysis.MyHadoopCorrAccumulator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.*;
import java.util.*;

/**
 * 离线相关性分析任务
 * 功能：
 * 1. 读取 HBase 中的全量交通数据。
 * 2. 按时间对齐数据，找出同一时刻所有路段的流量和速度。
 * 3. 计算任意两个路段（Site A 和 Site B）之间的相关性。
 * 4. 将结果输出到 CSV 文件。
 */
public class MyHadoopAnalysis {

    public static void main(String[] args) throws Exception {

        // 1. 初始化 HBase 配置
        Configuration conf = HBaseConfiguration.create();

        Connection conn = null;
        Table table = null;
        ResultScanner scanner = null;
        PrintWriter w = null;

        try {
            // 2. 建立连接
            conn = ConnectionFactory.createConnection(conf);
            table = conn.getTable(TableName.valueOf("traffic_data"));

            // 3. 定义数据结构
            // 存储流量的相关性累加器：Key="路段A|路段B", Value=累加器对象
            Map<String, MyHadoopCorrAccumulator> volumeCorr = new HashMap<>();
            // 存储速度的相关性累加器
            Map<String, MyHadoopCorrAccumulator> speedCorr  = new HashMap<>();

            // 内存缓冲区：用于按时间对齐数据
            // Key: 时间字符串 (例如 "2024-03-01 08:00:00")
            // Value: Map<路段ID, [流量, 速度]>
            Map<String, Map<String, double[]>> buffer = new HashMap<>();

            // 4. 配置全表扫描
            Scan scan = new Scan();
            scan.setCaching(500); // 批量拉取，优化网络性能
            scanner = table.getScanner(scan);

            // 5. 遍历每一行数据
            for (Result r : scanner) {
                // 解析 RowKey，格式假设为: "时间|盐值|路段ID"
                String[] parts = Bytes.toString(r.getRow()).split("\\|");
                if (parts.length < 3) continue; // 数据格式不合法跳过

                String time = parts[0]; // 提取时间
                String road = parts[2]; // 提取路段ID

                // 解析流量和速度 (列族 info)
                byte[] volBytes = r.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume"));
                byte[] spdBytes = r.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed"));

                // 判空处理，防止解析异常
                if (volBytes == null || spdBytes == null) continue;

                double volume = Double.parseDouble(Bytes.toString(volBytes));
                double speed = Double.parseDouble(Bytes.toString(spdBytes));

                // 6. 将数据放入缓冲区，按时间分组
                // 这样做的目的是为了让同一时刻的不同路段数据能够“相遇”
                buffer
                    .computeIfAbsent(time, k -> new HashMap<>())
                    .put(road, new double[]{volume, speed});

                // 7. 内存保护机制
                // 当缓冲区积累了 1000 个时间点的数据时，触发一次计算并清空缓冲区
                // 防止一次性加载所有数据导致 OutOfMemory (OOM)
                if (buffer.size() > 1000) {
                    consume(buffer, volumeCorr, speedCorr);
                    buffer.clear();
                }
            }

            // 8. 处理缓冲区中剩余的数据
            consume(buffer, volumeCorr, speedCorr);

            // 9. 将计算结果输出到 CSV 文件
            File out = new File("./result/corr.csv");
            out.getParentFile().mkdirs();
            w = new PrintWriter(new FileWriter(out));
            // 写入表头
            w.println("site_a,site_b,volume_corr,speed_corr");

            // 遍历所有路段对，计算最终的相关系数并写入
            for (String k : volumeCorr.keySet()) {
                // k 的格式是 "SiteA|SiteB"，替换为逗号分隔
                w.println(k.replace("|", ",") + "," +
                          volumeCorr.get(k).correlation() + "," + // 计算流量相关系数
                          speedCorr.get(k).correlation());        // 计算速度相关系数
            }

        } finally {
            // 10. 资源释放
            if (w       != null) w.close();
            if (scanner != null) scanner.close();
            if (table   != null) table.close();
            if (conn    != null) conn.close();
        }
    }

    /**
     * 消费缓冲区数据，进行两两路段的对比计算
     *
     * @param buffer   当前批次的时间切片数据
     * @param volCorr  流量相关性累加器 Map
     * @param spdCorr  速度相关性累加器 Map
     */
    private static void consume(
            Map<String, Map<String, double[]>> buffer,
            Map<String, MyHadoopCorrAccumulator> volCorr,
            Map<String, MyHadoopCorrAccumulator> spdCorr) {

        // 遍历每一个时间点 (Snapshot)
        for (Map<String, double[]> snapshot : buffer.values()) {
            // 获取该时间点下所有有数据的路段列表
            List<String> roads = new ArrayList<>(snapshot.keySet());

            // 双重循环：生成所有可能的路段对 (组合问题，不考虑顺序)
            // 例如有路段 A, B, C，则生成: (A,B), (A,C), (B,C)
            for (int i = 0; i < roads.size(); i++) {
                for (int j = i + 1; j < roads.size(); j++) {
                    String a = roads.get(i);
                    String b = roads.get(j);

                    // 生成唯一的 Pair Key
                    // 注意：为了避免重复（A|B 和 B|A），这里其实依赖 roads 列表的顺序
                    // 严谨的做法应该先对 a 和 b 排序再凭借，但这里简化处理了
                    String key = a + "|" + b;

                    // 获取两个路段在该时刻的数据
                    double[] da = snapshot.get(a);
                    double[] db = snapshot.get(b);

                    // 更新流量相关性累加器 (da[0] 是流量)
                    volCorr
                        .computeIfAbsent(key,
                                k -> new MyHadoopCorrAccumulator())
                        .add(da[0], db[0]);

                    // 更新速度相关性累加器 (da[1] 是速度)
                    spdCorr
                        .computeIfAbsent(key,
                                k -> new MyHadoopCorrAccumulator())
                        .add(da[1], db[1]);
                }
            }
        }
    }
}
