from pyspark.sql import SparkSession
from pyspark.sql.functions import col, corr, to_date

spark = SparkSession.builder.appName("TrafficCrossSiteCorrelation").getOrCreate()

# 通过 Spark-HBase Connector 读入为 DataFrame df
from hbase_reader import load_hbase_dataframe
df = load_hbase_dataframe(spark)

# -------------------------------------------------
# 1. 增加日期列（按天分析）
# -------------------------------------------------
df_day = df.withColumn("date", to_date(col("data_time")))

# -------------------------------------------------
# 2. 自连接：构造站点对（A, B）
#    - 同一天
#    - 不同站点
#    - 保证 A < B，避免重复
# -------------------------------------------------
df_a = df_day.alias("a")
df_b = df_day.alias("b")

joined = (
    df_a.join(
        df_b,
        (col("a.date") == col("b.date")) &
        (col("a.data_time") == col("b.data_time")) &
        (col("a.road_seg_id") < col("b.road_seg_id"))
    )
)

# -------------------------------------------------
# 3. 计算站点间皮尔逊相关系数（以 volume 为例）
# -------------------------------------------------
corr_df = (
    joined
    .groupBy(
        col("a.road_seg_id").alias("site_a"),
        col("b.road_seg_id").alias("site_b")
    )
    .agg(
        corr(col("a.volume"), col("b.volume")).alias("volume_corr"),
        corr(col("a.speed"), col("b.speed")).alias("speed_corr")
    )
)

# -------------------------------------------------
# 4. 按相关性排序输出
# -------------------------------------------------
corr_df = corr_df.orderBy(col("volume_corr").desc())

# corr_df.show(truncate=False)

corr_df.coalesce(1) \
    .write \
    .mode("overwrite") \
    .option("header", "true") \
    .csv("/app/spark_result/corr.csv")
