#!/bin/bash

# ==============================================================================
# 脚本名称: spark_run.sh (本地编译版)
# 功能描述: 使用本地编译好的 HBase-Spark Connector 提交 Spark 任务
# ==============================================================================

# 1. 确保图片输出目录存在
mkdir -p /app/preprocess

# 2. 配置本地编译好的 Jar 包路径
# (这是根据你刚才 build 脚本的输出确定的路径)
LOCAL_HBASE_JAR="/opt/spark/jars/hbase-spark-1.1.0-SNAPSHOT.jar"

# 检查 Jar 包是否存在，防止报错
if [ ! -f "$LOCAL_HBASE_JAR" ]; then
    echo "❌ Error: Custom HBase jar not found at $LOCAL_HBASE_JAR"
    echo "   Please run build_hbase_spark_connector.sh first."
    exit 1
fi

echo "✅ Found local HBase Connector: $LOCAL_HBASE_JAR"

# 3. 构建 spark-submit 参数
# --jars: 告诉 Spark 将本地的 jar 包分发到 Driver 和 Executor 的 classpath 中
# --packages: 仍然保留 postgres 驱动，因为它通常通过 Maven 下载比较方便（除非你也下载了它的 jar）
COMMON_ARGS=(
    --master local[*]
    --jars "$LOCAL_HBASE_JAR"
    --packages "org.postgresql:postgresql:42.6.0"
    --repositories "https://maven.aliyun.com/repository/center"
    --conf spark.hadoop.hbase.zookeeper.quorum=hbase
    --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181
    --conf spark.driver.extraJavaOptions=-Dlog4j2.level=WARN
)

echo "--- Starting Spark Jobs ---"

# 4. 提交任务

# 注意：请确保你的 .py 脚本中已将 format 写法修改为:
# .format("org.apache.hadoop.hbase.spark")

echo "[Job 1/4] Running Preprocess..."
spark-submit "${COMMON_ARGS[@]}" spark_preprocess.py

echo "[Job 2/4] Running Query..."
spark-submit "${COMMON_ARGS[@]}" spark_query.py

echo "[Job 3/4] Running Aggregate..."
spark-submit "${COMMON_ARGS[@]}" spark_aggregate.py

echo "[Job 4/4] Running Analysis..."
spark-submit "${COMMON_ARGS[@]}" spark_analysis.py
