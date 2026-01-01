package com.traffic;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import com.traffic.*;

/**
 * SortComparator
 *
 * MapReduce Shuffle 阶段的排序规则：
 * - 先按 roadSegId
 * - 再按 dataTime
 */
public class MyHadoopSortComparator extends WritableComparator {

    public MyHadoopSortComparator() {
        super(MyHadoopTrafficKey.class, true);
    }

    /**
     * 完全委托给 MyHadoopTrafficKey.compareTo()
     */
    @Override
    public int compare(Object a, Object b) {
        return ((MyHadoopTrafficKey) a).compareTo((MyHadoopTrafficKey) b);
    }
}
