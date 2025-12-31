#!/bin/bash

source env.sh

set -e  # 遇到错误立即退出

mvn -e clean package

JAR=target/traffic-hadoop-all.jar

export HADOOP_CLASSPATH=$HADOOP_CLASSPATH$(hbase mapredcp):/opt/postgresql-jdbc/postgresql-42.6.0.jar:$JAR


hadoop jar $JAR com.traffic.job.MyHadoopPreprocess
hadoop jar $JAR com.traffic.job.MyHadoopQuery
hadoop jar $JAR com.traffic.job.MyHadoopAggregate
hadoop jar $JAR com.traffic.job.MyHadoopAnalysis
