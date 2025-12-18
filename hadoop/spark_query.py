from pyspark.sql import SparkSession
from pyspark.sql.functions import col

spark = SparkSession.builder.appName('TrafficQuery').getOrCreate()

# 通过 Spark-HBase Connector 读入为 DataFrame df
from hbase_reader import load_hbase_dataframe
df = load_hbase_dataframe(spark)

# 获取所有有效的 road_seg_id（去重）
road_ids = (
    df.select('road_seg_id')
      .distinct()
      .rdd
      .map(lambda r: r[0])
      .collect()
)

if not road_ids:
    raise RuntimeError("未从 HBase 中读取到任何有效的 road_seg_id")

# 随机抽样一个 road_seg_id
import random
sample_road_id = random.choice(road_ids)
print(f"[INFO] 随机抽样得到的 road_seg_id: {sample_road_id}")

# 给定监测点、时间段，查询流量数据
result = df.filter(
    (col('road_seg_id') == sample_road_id) &
    (col('data_time').between('2024-03-01 08:00:00', '2024-03-02 08:00:00'))
)

# result.show()

# https://spark.apache.org/docs/latest/api/python/reference/pyspark.sql/api/pyspark.sql.DataFrameWriter.html
# `.coalesce(1)` 将 DataFrame 对象 result 的分区数合并为 1 个分区
# `.write` 返回一个 DataFrameWriter 对象，用于配置和执行写入操作（后续的 .mode, .option, .csv 都是 .write 的配置）
# `.mode("overwrite")` 将写入模式设置为“覆盖”（除此以外还有 "append", "ignore", "error" 等写入模式）
# `.option("header", "true")` 设置 CSV 文件的选项，此处表示在第一行写入列名（表头）
# `.csv("/app/spark_result")` 会将 DataFrame 以 CSV 格式写入指定目录 /app/spark_result
result.coalesce(1) \
    .write \
    .mode("overwrite") \
    .option("header", "true") \
    .csv("/app/spark_result/query.csv")
