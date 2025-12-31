#!/bin/bash

set -eux  # 遇到错误立即退出

# 检查 Hadoop, Zookeeper, HBase 是否已经运行
jps | grep NameNode && \
    jps | grep DataNode && \
    jps | grep QuorumPeerMain && \
    jps | grep HMaster && \
    jps | grep HRegionServer && \
    jps | grep ThriftServer && \
    jps | grep RESTServer || exit 1

source env.sh

mvn -e clean package

JAR=target/traffic-hadoop-all.jar

export HADOOP_CLASSPATH=$HADOOP_CLASSPATH$(hbase mapredcp):/opt/postgresql-jdbc/postgresql-42.6.0.jar:$JAR


hadoop jar $JAR com.traffic.job.MyHadoopPreprocess
hadoop jar $JAR com.traffic.job.MyHadoopQuery
hadoop jar $JAR com.traffic.job.MyHadoopAggregate
hadoop jar $JAR com.traffic.job.MyHadoopAnalysis
