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
 */
public class MyHadoopPreprocess
        extends Configured implements Tool {

    private static final Logger logger =
            Logger.getLogger(MyHadoopPreprocess.class.getName());

    private static final String LOG_FILE_PATH = "traffic_migration.log";
    private static final String HBASE_TABLE_NAME = "traffic_data";

    @Override
    public int run(String[] args) throws Exception {

        Configuration conf = getConf();

        try {
            FileHandler fh = new FileHandler(LOG_FILE_PATH);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (Exception ignore) {}

        logger.info("Starting TrafficDataPreprocess_Hadoop");

        DBConfiguration.configureDB(
                conf,
                "org.postgresql.Driver",
                "jdbc:postgresql://localhost:5432/traffic_db",
                "postgres",
                "postgres");

        Job job = Job.getInstance(conf, "TrafficDataPreprocess_Hadoop");
        job.setJarByClass(MyHadoopPreprocess.class);

        job.setMapperClass(MyHadoopTrafficMapper.class);
        job.setMapOutputKeyClass(MyHadoopTrafficKey.class);
        job.setMapOutputValueClass(MyHadoopTrafficRecord.class);

        String[] fields = {
                "road_seg_id", "data_time", "volume", "speed"
        };

        DBInputFormat.setInput(
                job,
                MyHadoopTrafficRecord.class,
                "raw_traffic_data",
                null, null,
                fields);

        job.setPartitionerClass(MyHadoopRoadIdPartitioner.class);
        job.setGroupingComparatorClass(MyHadoopGroupingComparator.class);
        job.setSortComparatorClass(MyHadoopSortComparator.class);

        TableMapReduceUtil.initTableReducerJob(
                HBASE_TABLE_NAME,
                MyHadoopTrafficReducer.class,
                job);

        logger.info("Job submitted, waiting for completion...");

        boolean success = job.waitForCompletion(true);

        // 【关键修复】失败立即中断
        if (!success) {
            logger.severe("MapReduce job failed, aborting.");
            throw new RuntimeException("TrafficDataPreprocess_Hadoop failed");
        }

        long total =
                job.getCounters()
                   .findCounter(MyHadoopTrafficMapper.DataCounters.TOTAL_ROWS)
                   .getValue();

        long augmented =
                job.getCounters()
                   .findCounter("Augmentation", "Generated_Rows")
                   .getValue();

        logger.info("Total rows loaded: " + total);
        logger.info("Augmented rows generated: " + augmented);

        return 0;
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        int res = ToolRunner.run(conf, new MyHadoopPreprocess(), args);
        System.exit(res);
    }
}
