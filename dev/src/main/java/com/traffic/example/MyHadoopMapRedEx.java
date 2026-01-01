package com.traffic.example;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.mapreduce.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.io.*;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;

/**
 * 这是一个用于统计 HBase 表行数的 MapReduce 示例程序。
 * 流程：读取 traffic_data 表 -> Map 阶段输出 1 -> Reduce 阶段求和 -> 结果写入 traffic_test_out 表
 */
public class MyHadoopMapRedEx {

    /**
     * Mapper 类
     * 继承自 TableMapper，专门用于读取 HBase 数据。
     * 输入：HBase 的行键 (ImmutableBytesWritable) 和 行数据 (Result)
     * 输出：Key (Text类型), Value (IntWritable类型)
     */
    public static class M extends TableMapper<Text, IntWritable> {

        /**
         * map 方法：每读取到 HBase 表中的一行数据，就会执行一次此方法。
         */
        @Override
        public void map(ImmutableBytesWritable k,
                        org.apache.hadoop.hbase.client.Result v,
                        Context c)
                throws java.io.IOException, InterruptedException {

            // 逻辑非常简单：不管读到什么数据，都输出一个固定的 Key "cnt" 和数值 1。
            // 类似于 WordCount 中的 "Hello": 1
            // 这样所有的 1 最终都会汇聚到同一个 Key "cnt" 下面。
            c.write(new Text("cnt"), new IntWritable(1));
        }
    }

    /**
     * Reducer 类
     * 继承自 TableReducer，专门用于将计算结果写回 HBase。
     * 输入：Key (Text), Value (IntWritable) -> 来自 Mapper 的输出
     * 输出：Key (ImmutableBytesWritable) -> HBase 的 RowKey（通常由 TableReducer 内部处理，这里传 null 即可）
     */
    public static class R
            extends TableReducer<Text, IntWritable, ImmutableBytesWritable> {

        @Override
        public void reduce(Text k,
                           Iterable<IntWritable> v,
                           Context c)
                throws java.io.IOException, InterruptedException {

            int sum = 0;
            // 遍历所有 value，进行累加
            for (IntWritable i : v) {
                sum += i.get();
            }

            // 创建 HBase 的写入对象 Put
            // 设置结果行的 RowKey 为 "row"
            Put p = new Put(Bytes.toBytes("row"));
            p.addColumn(Bytes.toBytes("info"),
                        Bytes.toBytes("c"),
                        Bytes.toBytes(sum));

            // 不再写 null key
            c.write(new ImmutableBytesWritable(p.getRow()), p);
        }
    }

    /**
     * Driver (主程序)
     * 负责配置 Job 的运行参数、Mapper、Reducer 以及输入输出路径。
     */
    public static void main(String[] args) throws Exception {
        // 1. 创建 HBase 配置对象，自动加载 hbase-site.xml 等配置
        Configuration conf = HBaseConfiguration.create();

        // 2. 创建 Job 实例，设置任务名称
        Job job = Job.getInstance(conf, "hbase-mr-min");
        job.setJarByClass(MyHadoopMapRedEx.class);

        // 3. 配置 Scan 对象（用于定义如何读取 HBase 数据）
        Scan scan = new Scan();
        scan.setCaching(500);        // 一次 RPC 请求读取 500 行，减少网络交互
        scan.setCacheBlocks(false);  // MR 任务属于离线批处理，不需要把数据缓存到 HBase 内存中，防止挤掉热点数据

        // 4. 初始化 Mapper 任务 (读取 HBase)
        TableMapReduceUtil.initTableMapperJob(
                "traffic_data",      // 输入表名：从哪张表读数据
                scan,                // 扫描控制器：怎么读
                M.class,             // Mapper 类
                Text.class,          // Mapper 输出的 Key 类型
                IntWritable.class,   // Mapper 输出的 Value 类型
                job);

        // 5. 初始化 Reducer 任务 (写入 HBase)
        // 注意：目标表 "traffic_test_out" 必须预先存在，且包含列族 "info"
        TableMapReduceUtil.initTableReducerJob(
                "traffic_test_out",  // 输出表名：结果写到哪张表
                R.class,             // Reducer 类
                job);

        // 6. 提交任务并等待完成
        job.waitForCompletion(true);
    }
}
