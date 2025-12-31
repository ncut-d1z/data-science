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

public class MyHadoopTrafficRecord implements DBWritable, WritableComparable<MyHadoopTrafficRecord> {
    public String road_seg_id;
    public String data_time;
    public double volume;
    public double speed;
    public boolean isVolumeNull = false;
    public boolean isSpeedNull = false;
    public boolean isIdNull = false;

    public void write(PreparedStatement statement) throws SQLException {
        statement.setString(1, road_seg_id);
        statement.setString(2, data_time);
        statement.setDouble(3, volume);
        statement.setDouble(4, speed);
    }

    public void readFields(ResultSet resultSet) throws SQLException {
        this.road_seg_id = resultSet.getString("road_seg_id");
        if (resultSet.wasNull()) this.isIdNull = true;

        this.data_time = resultSet.getString("data_time");

        this.volume = resultSet.getDouble("volume");
        if (resultSet.wasNull()) this.isVolumeNull = true;

        this.speed = resultSet.getDouble("speed");
        if (resultSet.wasNull()) this.isSpeedNull = true;
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
        this.road_seg_id = Text.readString(in);
        if (this.road_seg_id.isEmpty()) this.road_seg_id = null;
        this.data_time = Text.readString(in);
        this.volume = in.readDouble();
        this.speed = in.readDouble();
        this.isVolumeNull = in.readBoolean();
        this.isSpeedNull = in.readBoolean();
        this.isIdNull = in.readBoolean();
    }

    @Override
    public int compareTo(MyHadoopTrafficRecord o) {
        return this.data_time.compareTo(o.data_time);
    }
}
