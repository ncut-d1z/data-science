package com.traffic;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import com.traffic.*;

public class MyHadoopSortComparator extends WritableComparator {
    public MyHadoopSortComparator() {
        super(MyHadoopTrafficKey.class, true);
    }

    @Override
    public int compare(Object a, Object b) {
        return ((MyHadoopTrafficKey) a).compareTo((MyHadoopTrafficKey) b);
    }
}
