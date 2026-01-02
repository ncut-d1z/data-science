package com.traffic;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.logging.Logger;

import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableReducer;
import org.apache.hadoop.hbase.util.Bytes;
import com.traffic.*;

/**
 * Reducer 类：负责数据修复、增强并写入 HBase。
 *
 * <p>继承自 {@link TableReducer}，专门用于向 HBase 表输出数据。
 *
 * <p>核心处理逻辑：
 * 1. <b>Forward Fill (前向填充)</b>: 利用时间序列特性，用上一时刻的有效值填充当前时刻的缺失值。
 * 2. <b>Clipping (截断)</b>: 将速度和流量限制在合理的物理范围内。
 * 3. <b>Data Augmentation (数据扩增)</b>: 基于原始数据，生成未来时间点的模拟数据（带噪声）。
 * 4. <b>HBase Write</b>: 生成 RowKey 并写入 HBase。
 */
public class MyHadoopTrafficReducer
        extends TableReducer<MyHadoopTrafficKey,
                             MyHadoopTrafficRecord,
                             ImmutableBytesWritable> {

    private static final Logger logger =
            Logger.getLogger(MyHadoopTrafficReducer.class.getName());

    // HBase 列族名称
    private static final String CF_INFO = "info";

    // 解析输入字符串的时间格式
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // 生成 HBase RowKey 的时间格式（ISO-8601 风格，便于字典序排序）
    private final SimpleDateFormat hbaseRowKeyFmt =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    private final Random random = new Random();

    // 数据截断的阈值范围
    private final double volLower = 0.0,  volUpper = 5000.0;
    private final double spdLower = 0.0,  spdUpper = 150.0;

    /**
     * Reduce 函数
     * 假设输入数据已经按 (RoadID, Time) 排序。
     * 对于同一个 RoadID，values 迭代器会按时间先后顺序产出记录。
     */
    @Override
    protected void reduce(MyHadoopTrafficKey key,
                          Iterable<MyHadoopTrafficRecord> values,
                          Context context)
            throws IOException, InterruptedException {

        // 用于保存"上一次"的有效值，实现前向填充
        Double lastVolume = null;
        Double lastSpeed  = null;

        for (MyHadoopTrafficRecord rec : values) {

            // --- 1. Forward Fill (前向填充) 逻辑 ---

            // 如果当前 Volume 不为空，更新 lastVolume
            if (!rec.isVolumeNull) lastVolume = rec.volume;
            // 如果当前 Volume 为空，但在之前遇到过有效值，则使用旧值填充
            else if (lastVolume != null) {
                rec.volume = lastVolume;
                rec.isVolumeNull = false; // 标记为不再是 Null
            }

            // 对 Speed 做同样的逻辑
            if (!rec.isSpeedNull) lastSpeed = rec.speed;
            else if (lastSpeed != null) {
                rec.speed = lastSpeed;
                rec.isSpeedNull = false;
            }

            // 如果填充后依然缺失（说明是序列开头就缺失），则跳过该条记录
            if (rec.isVolumeNull || rec.isSpeedNull) continue;

            // --- 2. Clipping (数值截断/去噪) ---
            // 确保数值在合理范围内，消除极端异常值
            rec.volume = Math.max(volLower, Math.min(volUpper, rec.volume));
            rec.speed  = Math.max(spdLower, Math.min(spdUpper, rec.speed));

            try {
                Date dateObj = sdf.parse(rec.data_time);

                // --- 3. 写入原始清洗后的数据 ---
                writeRecord(context, rec.road_seg_id,
                            dateObj, rec.volume, rec.speed);

                // --- 4. Data Augmentation (数据扩增) ---
                // 为每一条真实数据生成 5 条未来的模拟数据
                for (int i = 1; i <= 5; i++) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(dateObj);
                    // 时间推移：每次增加 i * 2 天
                    cal.add(Calendar.DAY_OF_YEAR, i * 2);

                    // 添加随机噪声 (0.95 ~ 1.05 倍波动)
                    double noise = 0.95 + random.nextDouble() * 0.1;

                    // 写入模拟数据
                    writeRecord(context,
                                rec.road_seg_id,
                                cal.getTime(),
                                rec.volume * noise,
                                rec.speed  * noise);

                    // 记录生成的数据量
                    context.getCounter("Augmentation",
                                       "Generated_Rows").increment(1);
                }

            } catch (ParseException e) {
                // 如果时间格式错误，跳过处理，不中断任务
            }
        }
    }

    /**
     * 辅助方法：构建 RowKey 并向 Context 写入 Put 对象
     *
     * @param roadId 道路ID
     * @param date   时间对象
     * @param vol    流量值
     * @param spd    速度值
     */
    private void writeRecord(Context context,
                             String roadId,
                             Date date,
                             double vol,
                             double spd)
            throws IOException, InterruptedException {

        // 计算散列值 (Salt)，用于防止 RowKey 热点问题或用于分桶
        String salt = getSalt(roadId);
        String timeStr = hbaseRowKeyFmt.format(date);

        // --- 构建 HBase RowKey ---
        // 格式: Time | Salt | RoadID
        // 注意：通常将时间放在前面是为了按时间范围查询（Scan）。
        // Salt 的加入有助于区分同一时间下的不同道路。
        String rowKey = timeStr + "|" + salt + "|" + roadId;

        // 构建 Put 对象
        Put put = new Put(Bytes.toBytes(rowKey));
        // 添加列: info:volume
        put.addColumn(Bytes.toBytes(CF_INFO),
                      Bytes.toBytes("volume"),
                      Bytes.toBytes(String.valueOf(vol)));
        // 添加列: info:speed
        put.addColumn(Bytes.toBytes(CF_INFO),
                      Bytes.toBytes("speed"),
                      Bytes.toBytes(String.valueOf(spd)));

        // 【关键修复】TableReducer 要求输出 Key 必须是 ImmutableBytesWritable
        // 这里的 Key 是 RowKey，Value 是 Put 对象
        context.write(new ImmutableBytesWritable(put.getRow()), put);
    }

    /**
     * 辅助方法：根据 RoadID 生成简单的 Salt（散列值）。
     * 逻辑：取 RoadID 的 MD5 哈希的最后一个字节对 10 取模。
     * 结果范围：0-9
     */
    private String getSalt(String roadId) {
        if (roadId == null) return "0";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(roadId.getBytes());
            // 取哈希字节数组最后一位的绝对值模10
            return String.valueOf(Math.abs(hash[hash.length - 1]) % 10);
        } catch (NoSuchAlgorithmException e) {
            return "0"; // 降级处理
        }
    }
}
