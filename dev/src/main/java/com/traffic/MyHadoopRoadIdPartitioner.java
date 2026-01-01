package com.traffic;

import org.apache.hadoop.mapreduce.Partitioner;
import com.traffic.*;

/**
 * Partitioner
 *
 * 决定 Key 会被分配到哪个 Reducer
 * 保证相同 roadSegId 的数据一定进入同一个 Reducer
 */
public class MyHadoopRoadIdPartitioner
        extends Partitioner<MyHadoopTrafficKey, MyHadoopTrafficRecord> {

    @Override
    public int getPartition(MyHadoopTrafficKey key,
                            MyHadoopTrafficRecord value,
                            int numPartitions) {

        // 使用 roadSegId 的 hash，保证分布均匀
        return (key.roadSegId.hashCode() & Integer.MAX_VALUE) % numPartitions;
    }
}
