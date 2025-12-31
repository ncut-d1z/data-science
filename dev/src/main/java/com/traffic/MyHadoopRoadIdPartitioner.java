package com.traffic;

import org.apache.hadoop.mapreduce.Partitioner;
import com.traffic.*;

public class MyHadoopRoadIdPartitioner extends Partitioner<MyHadoopTrafficKey, MyHadoopTrafficRecord> {
    @Override
    public int getPartition(MyHadoopTrafficKey key, MyHadoopTrafficRecord value, int numPartitions) {
        return (key.roadSegId.hashCode() & Integer.MAX_VALUE) % numPartitions;
    }
}
