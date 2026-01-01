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
 * Reducer：
 * - Forward Fill
 * - Clipping
 * - 数据扩增
 * - 写入 HBase
 */
public class MyHadoopTrafficReducer
        extends TableReducer<MyHadoopTrafficKey,
                             MyHadoopTrafficRecord,
                             ImmutableBytesWritable> {

    private static final Logger logger =
            Logger.getLogger(MyHadoopTrafficReducer.class.getName());

    private static final String CF_INFO = "info";

    private final SimpleDateFormat sdf =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final SimpleDateFormat hbaseRowKeyFmt =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    private final Random random = new Random();

    private final double volLower = 0.0,  volUpper = 5000.0;
    private final double spdLower = 0.0,  spdUpper = 150.0;

    @Override
    protected void reduce(MyHadoopTrafficKey key,
                          Iterable<MyHadoopTrafficRecord> values,
                          Context context)
            throws IOException, InterruptedException {

        Double lastVolume = null;
        Double lastSpeed  = null;

        for (MyHadoopTrafficRecord rec : values) {

            // Forward Fill
            if (!rec.isVolumeNull) lastVolume = rec.volume;
            else if (lastVolume != null) {
                rec.volume = lastVolume;
                rec.isVolumeNull = false;
            }

            if (!rec.isSpeedNull) lastSpeed = rec.speed;
            else if (lastSpeed != null) {
                rec.speed = lastSpeed;
                rec.isSpeedNull = false;
            }

            if (rec.isVolumeNull || rec.isSpeedNull) continue;

            // Clipping
            rec.volume = Math.max(volLower, Math.min(volUpper, rec.volume));
            rec.speed  = Math.max(spdLower, Math.min(spdUpper, rec.speed));

            try {
                Date dateObj = sdf.parse(rec.data_time);

                writeRecord(context, rec.road_seg_id,
                            dateObj, rec.volume, rec.speed);

                // 数据扩增 (5倍)
                for (int i = 1; i <= 5; i++) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(dateObj);
                    cal.add(Calendar.DAY_OF_YEAR, i * 2);

                    double noise = 0.95 + random.nextDouble() * 0.1;
                    writeRecord(context,
                                rec.road_seg_id,
                                cal.getTime(),
                                rec.volume * noise,
                                rec.speed  * noise);

                    context.getCounter("Augmentation",
                                       "Generated_Rows").increment(1);
                }

            } catch (ParseException e) {
                // 时间解析失败，直接跳过
            }
        }
    }

    private void writeRecord(Context context,
                             String roadId,
                             Date date,
                             double vol,
                             double spd)
            throws IOException, InterruptedException {

        String salt = getSalt(roadId);
        String timeStr = hbaseRowKeyFmt.format(date);
        String rowKey = timeStr + "|" + salt + "|" + roadId;

        Put put = new Put(Bytes.toBytes(rowKey));
        put.addColumn(Bytes.toBytes(CF_INFO),
                      Bytes.toBytes("volume"),
                      Bytes.toBytes(String.valueOf(vol)));
        put.addColumn(Bytes.toBytes(CF_INFO),
                      Bytes.toBytes("speed"),
                      Bytes.toBytes(String.valueOf(spd)));

        // 【关键修复】禁止 null key
        context.write(new ImmutableBytesWritable(put.getRow()), put);
    }

    private String getSalt(String roadId) {
        if (roadId == null) return "0";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(roadId.getBytes());
            return String.valueOf(Math.abs(hash[hash.length - 1]) % 10);
        } catch (NoSuchAlgorithmException e) {
            return "0";
        }
    }
}
