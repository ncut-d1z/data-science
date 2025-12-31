package com.traffic;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import com.traffic.*;

public class MyHadoopSortComparator extends WritableComparator {
    public MyHadoopSortComparator() {
        super(MyHadoopTrafficKey.class, true);
    }

    @Override
    public int compare(WritableComparable w1, WritableComparable w2) {
        MyHadoopTrafficKey k1 = (MyHadoopTrafficKey) w1;
        MyHadoopTrafficKey k2 = (MyHadoopTrafficKey) w2;
        return k1.compareTo(k2);
    }
}
