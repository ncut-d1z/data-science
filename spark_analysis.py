from pyspark.sql import SparkSession
from pyspark.sql.functions import col, corr

spark = SparkSession.builder.appName('TrafficCorrelation').getOrCreate()

# 通过 Spark-HBase Connector 读入为 DataFrame df
from hbase_reader import load_hbase_dataframe
df = load_hbase_dataframe(spark)

# 计算皮尔逊相关系数，基于分钟级数据按日计算每两个监测点间的相关性，并按照相关性大小排序输出
cor_df = df.groupBy('road_seg_id').agg(
    corr('volume', 'speed').alias('pearson_corr')
)
cor_df.orderBy(col('pearson_corr').desc()).show()
