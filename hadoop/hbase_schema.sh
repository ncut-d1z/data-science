#!/bin/bash

# 新建名为 `traffic_data` 的表，
# 其中 `info` 是列族名
# 采用 SNAPPY 压缩方式和 FAST_DIFF 编码方式（对于时间序列数据而言，压缩效果较好）
hbase shell <<EOF
create 'traffic_data', 'info'
EOF

# 预期输出：
#   hbase:001:0> create 'traffic_data', 'info'
#   Created table traffic_data
#   Took 2.0436 seconds
#   => Hbase::Table - traffic_data
