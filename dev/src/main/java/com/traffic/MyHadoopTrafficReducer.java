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

public class MyHadoopTrafficReducer extends TableReducer<MyHadoopTrafficKey, MyHadoopTrafficRecord, ImmutableBytesWritable> {

    private static final Logger logger = Logger.getLogger(MyHadoopTrafficReducer.class.getName());
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat hbaseRowKeyFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private Random random = new Random();
    private static final String CF_INFO = "info";

    private double volLower = 0.0, volUpper = 5000.0;
    private double spdLower = 0.0, spdUpper = 150.0;

    @Override
    protected void setup(Context context) {
        // Reducer 初始化
    }

    @Override
    protected void reduce(MyHadoopTrafficKey key, Iterable<MyHadoopTrafficRecord> values, Context context)
            throws IOException, InterruptedException {

        Double lastVolume = null;
        Double lastSpeed = null;

        for (MyHadoopTrafficRecord rec : values) {
            // Forward Fill
            if (!rec.isVolumeNull) {
                lastVolume = rec.volume;
            } else if (lastVolume != null) {
                rec.volume = lastVolume;
                rec.isVolumeNull = false;
            }

            if (!rec.isSpeedNull) {
                lastSpeed = rec.speed;
            } else if (lastSpeed != null) {
                rec.speed = lastSpeed;
                rec.isSpeedNull = false;
            }

            if (rec.isVolumeNull || rec.isSpeedNull) continue;

            // Clipping
            rec.volume = Math.max(volLower, Math.min(rec.volume, volUpper));
            rec.speed = Math.max(spdLower, Math.min(rec.speed, spdUpper));

            try {
                Date dateObj = sdf.parse(rec.data_time);

                // 写入原始数据
                writeRecordToHBase(context, rec.road_seg_id, dateObj, rec.volume, rec.speed);

                // 数据扩增 (5倍)
                for (int i = 1; i <= 5; i++) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(dateObj);
                    cal.add(Calendar.DAY_OF_YEAR, i * 2);

                    double noise = 0.95 + random.nextDouble() * 0.1;
                    double augVolume = rec.volume * noise;
                    double augSpeed = rec.speed * noise;

                    writeRecordToHBase(context, rec.road_seg_id, cal.getTime(), augVolume, augSpeed);
                    context.getCounter("Augmentation", "Generated_Rows").increment(1);
                }

            } catch (ParseException e) {
                // Ignore parse errors
            }
        }
    }

    private void writeRecordToHBase(Context context, String roadId, Date date, double vol, double spd)
            throws IOException, InterruptedException {

        String salt = getSalt(roadId);
        String timeStr = hbaseRowKeyFmt.format(date);
        String rowKeyStr = String.format("%s|%s|%s", timeStr, salt, roadId);

        Put put = new Put(Bytes.toBytes(rowKeyStr));
        put.addColumn(Bytes.toBytes(CF_INFO), Bytes.toBytes("volume"), Bytes.toBytes(String.valueOf(vol)));
        put.addColumn(Bytes.toBytes(CF_INFO), Bytes.toBytes("speed"), Bytes.toBytes(String.valueOf(spd)));

        context.write(new ImmutableBytesWritable(Bytes.toBytes(rowKeyStr)), put);
    }

    private String getSalt(String roadId) {
        if (roadId == null) return "0";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(roadId.getBytes());
            int num = Math.abs(hash[hash.length - 1]);
            return String.valueOf(num % 10);
        } catch (NoSuchAlgorithmException e) {
            return "0";
        }
    }
}
