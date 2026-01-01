package com.traffic.example;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.mapreduce.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.io.*;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

public class MyHadoopMapRedEx {

    public static class M extends TableMapper<Text, IntWritable> {
        public void map(ImmutableBytesWritable k, org.apache.hadoop.hbase.client.Result v, Context c)
                throws java.io.IOException, InterruptedException {
            c.write(new Text("cnt"), new IntWritable(1));
        }
    }

    public static class R extends TableReducer<Text, IntWritable, ImmutableBytesWritable> {
        public void reduce(Text k, Iterable<IntWritable> v, Context c)
                throws java.io.IOException, InterruptedException {
            int sum = 0; for (IntWritable i : v) sum += i.get();
            Put p = new Put(Bytes.toBytes("row"));
            p.addColumn(Bytes.toBytes("f"), Bytes.toBytes("c"), Bytes.toBytes(sum));
            c.write(null, p);
        }
    }

    public static void main(String[] a) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        Job job = Job.getInstance(conf, "hbase-mr-min");
        job.setJarByClass(MyHadoopMapRedEx.class);
        TableMapReduceUtil.initTableMapperJob("traffic_data", null, M.class, Text.class, IntWritable.class, job);
        TableMapReduceUtil.initTableReducerJob("traffic_test_out", R.class, job);
        job.waitForCompletion(true);
    }
}
