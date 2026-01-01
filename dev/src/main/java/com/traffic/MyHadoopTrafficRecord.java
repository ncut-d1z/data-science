package com.traffic;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;
import com.traffic.*;

/**
 * Traffic Record：
 * - 既是 DBWritable（JDBC 读）
 * - 又是 WritableComparable（MR Shuffle）
 */
public class MyHadoopTrafficRecord
        implements DBWritable, WritableComparable<MyHadoopTrafficRecord> {

    public String road_seg_id;
    public String data_time;
    public double volume;
    public double speed;

    // 数据质量标记
    public boolean isVolumeNull = false;
    public boolean isSpeedNull  = false;
    public boolean isIdNull     = false;

    @Override
    public void write(PreparedStatement statement) throws SQLException {
        statement.setString(1, road_seg_id);
        statement.setString(2, data_time);
        statement.setDouble(3, volume);
        statement.setDouble(4, speed);
    }

    @Override
    public void readFields(ResultSet rs) throws SQLException {
        road_seg_id = rs.getString("road_seg_id");
        if (rs.wasNull()) isIdNull = true;

        data_time = rs.getString("data_time");

        volume = rs.getDouble("volume");
        if (rs.wasNull()) isVolumeNull = true;

        speed = rs.getDouble("speed");
        if (rs.wasNull()) isSpeedNull = true;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        Text.writeString(out, road_seg_id == null ? "" : road_seg_id);
        Text.writeString(out, data_time == null ? "" : data_time);
        out.writeDouble(volume);
        out.writeDouble(speed);
        out.writeBoolean(isVolumeNull);
        out.writeBoolean(isSpeedNull);
        out.writeBoolean(isIdNull);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        road_seg_id = Text.readString(in);
        if (road_seg_id.isEmpty()) road_seg_id = null;

        data_time = Text.readString(in);
        volume = in.readDouble();
        speed  = in.readDouble();
        isVolumeNull = in.readBoolean();
        isSpeedNull  = in.readBoolean();
        isIdNull     = in.readBoolean();
    }

    /**
     * 仅用于排序（通常不会用到）
     */
    @Override
    public int compareTo(MyHadoopTrafficRecord o) {
        return this.data_time.compareTo(o.data_time);
    }
}
