package com.traffic;

import java.io.IOException;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Mapper;
import com.traffic.*;

public class MyHadoopTrafficMapper extends Mapper<LongWritable, MyHadoopTrafficRecord, MyHadoopTrafficKey, MyHadoopTrafficRecord> {

    // 公开枚举以便主类访问
    public enum DataCounters {
        TOTAL_ROWS, MISSING_VOLUME, MISSING_SPEED, MISSING_ID, DROPPED_NO_ID
    }

    @Override
    protected void map(LongWritable key, MyHadoopTrafficRecord value, Context context) throws IOException, InterruptedException {
        context.getCounter(DataCounters.TOTAL_ROWS).increment(1);

        if (value.isVolumeNull) context.getCounter(DataCounters.MISSING_VOLUME).increment(1);
        if (value.isSpeedNull) context.getCounter(DataCounters.MISSING_SPEED).increment(1);
        if (value.isIdNull) context.getCounter(DataCounters.MISSING_ID).increment(1);

        if (value.isIdNull || value.road_seg_id == null) {
            context.getCounter(DataCounters.DROPPED_NO_ID).increment(1);
            return;
        }

        MyHadoopTrafficKey compositeKey = new MyHadoopTrafficKey(value.road_seg_id, value.data_time);
        context.write(compositeKey, value);
    }
}
