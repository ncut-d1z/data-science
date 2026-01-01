package com.traffic;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import com.traffic.*;

/**
 * 复合 Key：
 * - roadSegId
 * - dataTime
 *
 * 用于实现 Secondary Sort
 */
public class MyHadoopTrafficKey
        implements WritableComparable<MyHadoopTrafficKey> {

    public String roadSegId;
    public String dataTime;

    public MyHadoopTrafficKey() {}

    public MyHadoopTrafficKey(String id, String time) {
        // 防止 null 进入 compareTo / hashCode
        this.roadSegId = id == null ? "NULL" : id;
        this.dataTime = time == null ? "0000-00-00 00:00:00" : time;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        Text.writeString(out, roadSegId);
        Text.writeString(out, dataTime);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        this.roadSegId = Text.readString(in);
        this.dataTime = Text.readString(in);
    }

    /**
     * 排序规则：
     * 1. roadSegId
     * 2. dataTime
     */
    @Override
    public int compareTo(MyHadoopTrafficKey o) {
        int cmp = this.roadSegId.compareTo(o.roadSegId);
        if (cmp != 0) return cmp;
        return this.dataTime.compareTo(o.dataTime);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MyHadoopTrafficKey)) return false;
        MyHadoopTrafficKey k = (MyHadoopTrafficKey) o;
        return roadSegId.equals(k.roadSegId)
            && dataTime.equals(k.dataTime);
    }

    @Override
    public int hashCode() {
        return 31 * roadSegId.hashCode() + dataTime.hashCode();
    }
}
