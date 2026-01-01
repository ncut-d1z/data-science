package com.traffic;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import com.traffic.*;

/**
 * GroupingComparator
 *
 * 用于 MapReduce 的二次排序（Secondary Sort）：
 * - 决定 Reduce 阶段哪些 Key 会被分到同一个 reduce() 调用
 * - 这里只按 roadSegId 分组，忽略时间字段
 */
public class MyHadoopGroupingComparator extends WritableComparator {

    /**
     * 必须调用父类构造器并启用实例化
     */
    public MyHadoopGroupingComparator() {
        super(MyHadoopTrafficKey.class, true);
    }

    /**
     * Reduce 分组逻辑：
     * 只比较 roadSegId，相同 roadSegId 的记录会进入同一个 reduce()
     */
    @Override
    public int compare(Object a, Object b) {
        MyHadoopTrafficKey k1 = (MyHadoopTrafficKey) a;
        MyHadoopTrafficKey k2 = (MyHadoopTrafficKey) b;
        return k1.roadSegId.compareTo(k2.roadSegId);
    }
}
