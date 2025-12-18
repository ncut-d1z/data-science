from pyspark.sql import SparkSession
from pyspark.sql.functions import window, sum as _sum, avg

spark = SparkSession.builder.appName('TrafficAggregate').getOrCreate()

# 通过 Spark-HBase Connector 读入为 DataFrame df
from hbase_reader import load_hbase_dataframe
df = load_hbase_dataframe(spark)

# 使用PySpark编写程序，从HBASE中提取全量数据，
# 然后按照监测点ID分组，将分钟数据聚合成15分钟数据
# （聚合后输出15分钟时间段里的最早的时间戳和最晚的时间戳），
# 其中流量数据聚合逻辑为15分钟流量值的求和，速度数据聚合逻辑为15分钟速度值的均值
agg15 = df.groupBy(
    'road_seg_id',
    window('data_time', '15 minutes')
).agg(
    _sum('volume').alias('sum_volume_15min'),
    avg('speed').alias('avg_speed_15min')
)

# agg15.show()

agg15.coalesce(1) \
    .write \
    .mode("overwrite") \
    .option("header", "true") \
    .csv("/app/spark_result/agg.csv")
