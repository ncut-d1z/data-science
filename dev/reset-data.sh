#!/bin/bash
# 配置脚本（在容器执行）

# 1. 删除 Hadoop 数据目录
rm -rf ${HADOOP_TEMP}/*
rm -rf ${HADOOP_NAMENODE}/*
rm -rf ${HADOOP_DATANODE}/*

# 2. 删除 ZooKeeper 数据 (清除 HBase 的元数据记录)
rm -rf ${ZOO_DAT_DIR}/*
rm -rf ${ZOO_LOG_DIR}/*
# 重建 myid
echo "1" > ${ZOO_DAT_DIR}/myid

# 3. 删除 HBase 的本地临时文件 (如果有)
rm -rf /tmp/hbase-*

# 4. 格式化
hdfs namenode -format
