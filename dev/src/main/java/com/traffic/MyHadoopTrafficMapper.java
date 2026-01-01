package com.traffic;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Mapper;
import com.traffic.*;

/**
 * Mapper：
 * - 从 JDBC(DBInputFormat) 读取数据
 * - 构造 CompositeKey
 * - 统计数据质量 Counter
 */
public class MyHadoopTrafficMapper
        extends Mapper<LongWritable,
                       MyHadoopTrafficRecord,
                       MyHadoopTrafficKey,
                       MyHadoopTrafficRecord> {

    /**
     * 数据质量统计 Counter
     */
    public enum DataCounters {
        TOTAL_ROWS,
        MISSING_VOLUME,
        MISSING_SPEED,
        MISSING_ID,
        DROPPED_NO_ID
    }

    @Override
    protected void map(LongWritable key,
                       MyHadoopTrafficRecord value,
                       Context context)
            throws IOException, InterruptedException {

        context.getCounter(DataCounters.TOTAL_ROWS).increment(1);

        if (value.isVolumeNull) context.getCounter(DataCounters.MISSING_VOLUME).increment(1);
        if (value.isSpeedNull)  context.getCounter(DataCounters.MISSING_SPEED).increment(1);
        if (value.isIdNull)     context.getCounter(DataCounters.MISSING_ID).increment(1);

        // 没有 roadSegId 的数据直接丢弃
        if (value.isIdNull || value.road_seg_id == null) {
            context.getCounter(DataCounters.DROPPED_NO_ID).increment(1);
            return;
        }

        MyHadoopTrafficKey compositeKey =
                new MyHadoopTrafficKey(value.road_seg_id, value.data_time);

        context.write(compositeKey, value);
    }
}
