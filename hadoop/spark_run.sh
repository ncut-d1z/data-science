#!/bin/bash

# 确保 /app/preprocess 目录存在，用于存放生成的图片
mkdir -p /app/preprocess

# 提交 Spark 任务
# 引入了两个包：
# 1. hbase-spark: 用于读写 HBase
# 2. postgresql: 用于读取 Postgres 数据库
spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.5.0,org.postgresql:postgresql:42.6.0 \
  --conf spark.hadoop.hbase.zookeeper.quorum=hbase \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_preprocess.py

# 在本地启动一个Spark进程，加载HBase连接器，
# 然后运行spark_query.py脚本，
# 让该脚本能够通过ZooKeeper连接到名为hbase的HBase服务并进行数据操作。
#
# spark-submit 是Spark 提供的官方脚本命令，用于向集群（或本地）提交并运行打包好的 Spark 应用程序（通常是 .py 或 .jar 文件）
# `--master local[*]` 用来设置 Spark 的运行模式。`local[*]` 表示在本地机器上以单机模式运行，其中 * 表示使用所有可用的 CPU 逻辑核心进行计算。
# `--packages org.apache.hbase:hbase-spark:2.5.0` 用来动态指定依赖库。Spark 会自动从 Maven 中央仓库下载 hbase-spark 这个官方连接器库（版本 2.5.0），它提供了 Spark 读写 HBase 所需的 API。这省去了手动将jar包放入classpath的麻烦。
# `--conf spark.hadoop.hbase.zookeeper.quorum=hbase` 用来配置 HBase 的连接地址。这是最关键的一项配置，它告诉 Spark：HBase 所使用的 ZooKeeper 集群地址是 hbase。在 Docker Compose 环境中，hbase 正是 HBase 服务的容器名称。因此，Spark 容器可以通过这个主机名直接访问到 HBase 容器。
# `--conf spark.hadoop.hbase.zookeeper.property.clientPort=2181` 用来配置 ZooKeeper 的连接端口。指定 ZooKeeper 的服务端口为默认的 2181。这个端口需要与 HBase 容器中 ZooKeeper 的实际暴露端口一致。
spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.5.0 \
  --conf spark.hadoop.hbase.zookeeper.quorum=hbase \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_query.py

spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.5.0 \
  --conf spark.hadoop.hbase.zookeeper.quorum=hbase \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_aggregate.py

spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.5.0 \
  --conf spark.hadoop.hbase.zookeeper.quorum=hbase \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_analysis.py
