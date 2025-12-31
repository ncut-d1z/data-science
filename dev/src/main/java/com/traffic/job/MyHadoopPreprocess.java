package com.traffic.job;

import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.apache.hadoop.mapreduce.lib.db.DBInputFormat;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import com.traffic.*;

public class MyHadoopPreprocess extends Configured implements Tool {

    private static final Logger logger = Logger.getLogger(MyHadoopPreprocess.class.getName());
    private static String LOG_FILE_PATH = "traffic_migration.log";
    private static final String HBASE_TABLE_NAME = "traffic_data";

    @Override
    public int run(String[] args) throws Exception {
        Configuration conf = getConf();

        try {
            FileHandler fh = new FileHandler(LOG_FILE_PATH);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (Exception e) {}

        logger.info("--- [1] Reading data from Postgres via JDBC (MapReduce) ---");

        DBConfiguration.configureDB(conf, "org.postgresql.Driver",
                "jdbc:postgresql://localhost:5432/traffic_db", "postgres", "postgres");

        Job job = Job.getInstance(conf, "TrafficDataPreprocess_Hadoop");
        job.setJarByClass(MyHadoopPreprocess.class);

        // 使用拆分后的新类
        job.setMapperClass(MyHadoopTrafficMapper.class);
        job.setMapOutputKeyClass(MyHadoopTrafficKey.class);
        job.setMapOutputValueClass(MyHadoopTrafficRecord.class);

        String[] fields = {"road_seg_id", "data_time", "volume", "speed"};
        DBInputFormat.setInput(job, MyHadoopTrafficRecord.class, "raw_traffic_data", null, null, fields);

        // 使用拆分后的 Partition 和 Comparator 类
        job.setPartitionerClass(MyHadoopRoadIdPartitioner.class);
        job.setGroupingComparatorClass(MyHadoopGroupingComparator.class);
        job.setSortComparatorClass(MyHadoopSortComparator.class);

        // 使用拆分后的 Reducer 类
        TableMapReduceUtil.initTableReducerJob(
                HBASE_TABLE_NAME,
                MyHadoopTrafficReducer.class,
                job);

        logger.info("Job initialized. Starting...");
        boolean success = job.waitForCompletion(true);

        if (success) {
            long totalRows = job.getCounters().findCounter(MyHadoopTrafficMapper.DataCounters.TOTAL_ROWS).getValue();
            long augmented = job.getCounters().findCounter("Augmentation", "Generated_Rows").getValue();

            logger.info("--- Summary ---");
            logger.info("Total rows loaded: " + totalRows);
            logger.info("Augmented rows generated: " + augmented);
        } else {
            logger.severe("Job failed!");
        }

        return success ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        int res = ToolRunner.run(conf, new MyHadoopPreprocess(), args);
        System.exit(res);
    }
}
