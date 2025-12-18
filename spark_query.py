from pyspark.sql import SparkSession
from pyspark.sql.functions import col

spark = SparkSession.builder.appName('TrafficQuery').getOrCreate()

# 通过 Spark-HBase Connector 读入为 DataFrame df
from hbase_reader import load_hbase_dataframe
df = load_hbase_dataframe(spark)

# 给定监测点、时间段，查询流量数据
result = df.filter(
    (col('road_seg_id') == 'TEST_ID') &
    (col('data_time').between('2024-03-01 08:00:00', '2024-03-01 09:00:00'))
)

result.show()
