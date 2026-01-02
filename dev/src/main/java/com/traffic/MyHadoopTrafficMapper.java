package com.traffic;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Mapper;
import com.traffic.*;

/**
 * Mapper 类：负责原始数据的清洗、过滤和键值对构建。
 *
 * <p>功能流程：
 * 1. 接收 DBInputFormat 传入的数据库记录。
 * 2. 统计数据质量（总行数、字段缺失数）。
 * 3. 过滤掉缺少关键标识（road_seg_id）的无效数据。
 * 4. 输出以 (RoadID + Time) 为组合键的记录，以便 Reducer 能按时间顺序处理。
 *
 * <p>泛型说明：
 * - Input Key: LongWritable (数据库读取时的行号或偏移量)
 * - Input Value: MyHadoopTrafficRecord (自定义的 Writable 类型，映射数据库行)
 * - Output Key: MyHadoopTrafficKey (自定义组合键，用于二次排序)
 * - Output Value: MyHadoopTrafficRecord (透传原始数据)
 */
public class MyHadoopTrafficMapper
        extends Mapper<LongWritable,
                       MyHadoopTrafficRecord,
                       MyHadoopTrafficKey,
                       MyHadoopTrafficRecord> {

    /**
     * 数据质量统计计数器（Counter）。
     * 用于在 Hadoop 任务结束后查看数据的健康状况。
     */
    public enum DataCounters {
        TOTAL_ROWS,      // 处理的总行数
        MISSING_VOLUME,  // 交通流量(volume)缺失的行数
        MISSING_SPEED,   // 交通速度(speed)缺失的行数
        MISSING_ID,      // 道路ID(road_seg_id)缺失的行数
        DROPPED_NO_ID    // 因缺少道路ID而被丢弃的行数
    }

    /**
     * Map 函数核心逻辑
     */
    @Override
    protected void map(LongWritable key,
                       MyHadoopTrafficRecord value,
                       Context context)
            throws IOException, InterruptedException {

        // 1. 全局计数器加 1
        context.getCounter(DataCounters.TOTAL_ROWS).increment(1);

        // 2. 检查各字段是否为 Null，并累加对应计数器
        if (value.isVolumeNull) context.getCounter(DataCounters.MISSING_VOLUME).increment(1);
        if (value.isSpeedNull)  context.getCounter(DataCounters.MISSING_SPEED).increment(1);
        if (value.isIdNull)     context.getCounter(DataCounters.MISSING_ID).increment(1);

        // 3. 数据清洗：如果缺少 road_seg_id，数据无法归属，直接丢弃
        if (value.isIdNull || value.road_seg_id == null) {
            context.getCounter(DataCounters.DROPPED_NO_ID).increment(1);
            return; // 提前返回，不进行 write
        }

        // 4. 构建组合键 (Composite Key)
        // 包含 road_seg_id 和 data_time。
        // 配合自定义的 Partitioner 和 GroupingComparator，可以确保：
        // (1) 相同 road_seg_id 的数据进入同一个 Reducer。
        // (2) 在 Reducer 中迭代时，数据是严格按照 data_time 排序的。
        MyHadoopTrafficKey compositeKey =
                new MyHadoopTrafficKey(value.road_seg_id, value.data_time);

        // 5. 输出键值对
        context.write(compositeKey, value);
    }
}
