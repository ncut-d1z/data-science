package com.traffic.job;

import java.util.logging.*;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.db.*;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;

import com.traffic.*;

/**
 * JDBC → MapReduce → HBase 预处理任务
 * <p>
 * 功能描述：
 * 1. 使用 DBInputFormat 从 PostgreSQL 的 'raw_traffic_data' 表读取原始数据。
 * 2. Mapper 阶段进行数据清洗和封装。
 * 3. 经过自定义的分区（Partitioner）和排序（Comparator），确保数据有序进入 Reducer。
 * 4. Reducer 阶段处理数据（如数据扩增），并使用 TableReducer 将结果写入 HBase 表 'traffic_data'。
 * </p>
 */
public class MyHadoopPreprocess
        extends Configured implements Tool {

    private static final Logger logger =
            Logger.getLogger(MyHadoopPreprocess.class.getName());

    // 日志文件路径和 HBase 目标表名
    private static final String LOG_FILE_PATH = "traffic_migration.log";
    private static final String HBASE_TABLE_NAME = "traffic_data";

    @Override
    public int run(String[] args) throws Exception {

        // 获取 Hadoop 配置信息
        Configuration conf = getConf();

        // --- 1. 配置本地日志记录 ---
        try {
            FileHandler fh = new FileHandler(LOG_FILE_PATH);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (Exception ignore) {}

        logger.info("Starting TrafficDataPreprocess_Hadoop");

        // --- 2. 配置 JDBC 连接 (输入源) ---
        // 告诉 MapReduce 如何连接到 PostgreSQL 数据库
        DBConfiguration.configureDB(
                conf,
                "org.postgresql.Driver", // 数据库驱动
                "jdbc:postgresql://localhost:5432/traffic_db", // JDBC URL
                "postgres", // 用户名
                "postgres"); // 密码

        // --- 3. 创建 Job 实例 ---
        Job job = Job.getInstance(conf, "TrafficDataPreprocess_Hadoop");
        job.setJarByClass(MyHadoopPreprocess.class);

        // --- 4. 配置 Mapper ---
        job.setMapperClass(MyHadoopTrafficMapper.class);
        // 设置 Mapper 输出的 Key 类型 (自定义的组合 Key)
        job.setMapOutputKeyClass(MyHadoopTrafficKey.class);
        // 设置 Mapper 输出的 Value 类型 (自定义的 DBWritable 对象)
        job.setMapOutputValueClass(MyHadoopTrafficRecord.class);

        // 定义要从数据库读取的字段
        String[] fields = {
                "road_seg_id", "data_time", "volume", "speed"
        };

        // --- 5. 配置 DBInputFormat (数据读取方式) ---
        // 指定将数据库记录映射到哪个类 (MyHadoopTrafficRecord)，以及读取哪张表
        DBInputFormat.setInput(
                job,
                MyHadoopTrafficRecord.class, // 接收数据的类 (需实现 DBWritable)
                "raw_traffic_data",          // 输入表名
                null, null,                  // 条件和排序 (这里为空，表示读取全表)
                fields);                     // 读取的列

        // --- 6. 配置 Shuffle 阶段 (分区与排序) ---
        // Partitioner: 决定数据发往哪个 Reducer (通常按 road_seg_id 分区)
        job.setPartitionerClass(MyHadoopRoadIdPartitioner.class);
        // GroupingComparator: 决定 Reducer 中哪些 Key 聚合为一组
        job.setGroupingComparatorClass(MyHadoopGroupingComparator.class);
        // SortComparator: 决定 Map 输出到达 Reducer 前的排序规则 (通常也就是二次排序逻辑)
        job.setSortComparatorClass(MyHadoopSortComparator.class);

        // --- 7. 配置 Reducer (输出到 HBase) ---
        // 使用 HBase 提供的工具类初始化 TableReducer
        TableMapReduceUtil.initTableReducerJob(
                HBASE_TABLE_NAME,             // 目标 HBase 表名
                MyHadoopTrafficReducer.class, // Reducer 类
                job);

        logger.info("Job submitted, waiting for completion...");

        // --- 8. 提交任务并等待完成 ---
        boolean success = job.waitForCompletion(true);

        // 【关键修复】如果任务失败，抛出异常以中断后续流程
        // 这在 Shell 脚本串联执行时非常重要
        if (!success) {
            logger.severe("MapReduce job failed, aborting.");
            throw new RuntimeException("TrafficDataPreprocess_Hadoop failed");
        }

        // --- 9. 获取并打印计数器统计信息 ---
        // 从 Mapper 中获取处理的总行数
        long total =
                job.getCounters()
                   .findCounter(MyHadoopTrafficMapper.DataCounters.TOTAL_ROWS)
                   .getValue();

        // 从 Reducer 中获取扩增生成的行数
        long augmented =
                job.getCounters()
                   .findCounter("Augmentation", "Generated_Rows")
                   .getValue();

        logger.info("Total rows loaded: " + total);
        logger.info("Augmented rows generated: " + augmented);

        return 0; // 0 表示成功
    }

    /**
     * 程序入口
     * 使用 ToolRunner 运行，它会自动处理通用命令行参数 (如 -Dproperty=value)
     */
    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        int res = ToolRunner.run(conf, new MyHadoopPreprocess(), args);
        System.exit(res);
    }
}
