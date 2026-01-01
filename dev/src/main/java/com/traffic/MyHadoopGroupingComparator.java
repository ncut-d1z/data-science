package com.traffic;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import com.traffic.*;

public class MyHadoopGroupingComparator extends WritableComparator {
    public MyHadoopGroupingComparator() {
        super(MyHadoopTrafficKey.class, true);
    }

    @Override
    public int compare(Object a, Object b) {
        MyHadoopTrafficKey k1 = (MyHadoopTrafficKey) a;
        MyHadoopTrafficKey k2 = (MyHadoopTrafficKey) b;
        return k1.roadSegId.compareTo(k2.roadSegId);
    }
}
