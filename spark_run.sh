#!/bin/bash

spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.4.17 \
  --conf spark.hadoop.hbase.zookeeper.quorum=localhost \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_query.py

spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.4.17 \
  --conf spark.hadoop.hbase.zookeeper.quorum=localhost \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_aggregate.py

spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.4.17 \
  --conf spark.hadoop.hbase.zookeeper.quorum=localhost \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_analysis.py
