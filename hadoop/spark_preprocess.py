import os
import hashlib
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

from pyspark.sql import SparkSession, Window
from pyspark.sql import functions as F
from pyspark.sql.types import StringType, DoubleType, TimestampType
from pyspark.sql.functions import col, lit, udf, when, count, min, max, avg, countDistinct, sum as spark_sum, last, date_format

# HBase 配置
HBASE_TABLE = "traffic_data"
# 定义 HBase Catalog (用于写入)
# rowkey 映射到 Spark DataFrame 的 rowkey 列
# info:volume 映射到 Spark DataFrame 的 volume 列
# info:speed 映射到 Spark DataFrame 的 speed 列
write_catalog = "".join("""
{
  "table":{"namespace":"default", "name":"traffic_data"},
  "rowkey":"rowkey",
  "columns":{
    "rowkey":{"cf":"rowkey", "col":"key", "type":"string"},
    "volume":{"cf":"info", "col":"volume", "type":"string"},
    "speed":{"cf":"info", "col":"speed", "type":"string"}
  }
}
""".split())

def get_spark_session():
    return SparkSession.builder \
        .appName("TrafficDataPreprocess") \
        .getOrCreate()

# ----------------------------
# UDF: 盐值计算 (对应原代码中的 salt 函数)
# ----------------------------
def calculate_salt(road_id, salt_num=10):
    if road_id is None:
        return "0"
    return str(int(hashlib.md5(road_id.encode('utf-8')).hexdigest(), 16) % salt_num)

salt_udf = udf(calculate_salt, StringType())

def main():
    spark = get_spark_session()
    spark.sparkContext.setLogLevel("WARN")

    print("--- [1] Reading data from Postgres via JDBC ---")
    # 读取 Postgres 数据
    # 注意：在真实分布式环境中，建议配置 partitionColumn, lowerBound, upperBound, numPartitions
    # 以便并行读取。这里简化为单任务读取。
    df = spark.read \
        .format("jdbc") \
        .option("url", "jdbc:postgresql://postgres:5432/traffic_db") \
        .option("dbtable", "raw_traffic_data") \
        .option("user", "postgres") \
        .option("password", "postgres") \
        .option("driver", "org.postgresql.Driver") \
        .load()

    # 缓存原始数据，因为后面要多次使用（统计、绘图、清洗）
    df.cache()
    print(f"Total rows loaded: {df.count()}")

    # ----------------------------
    # 统计概况 (对应原 summary_sql)
    # ----------------------------
    print("--- Statistics Summary ---")
    df.select(
        count("*").alias("count_line"),
        countDistinct("road_seg_id").alias("count_site"),
        min("data_time").alias("min_time"),
        max("data_time").alias("max_time"),
        avg("volume").alias("avg_volume"),
        avg("speed").alias("avg_speed")
    ).show()

    # ----------------------------
    # 缺失值统计 (对应原 missing_sql)
    # ----------------------------
    print("--- Missing Values ---")
    df.select(
        spark_sum(when(col("volume").isNull(), 1).otherwise(0)).alias("volume_null"),
        spark_sum(when(col("speed").isNull(), 1).otherwise(0)).alias("speed_null"),
        spark_sum(when(col("road_seg_id").isNull(), 1).otherwise(0)).alias("id_null")
    ).show()

    # 打印描述性统计 (类似 pandas describe)
    # 注意：exclude timestamp/string columns for describe to save time or select specific ones
    df.select("volume", "speed").summary().show()

    # ----------------------------
    # 2. 异常检测 (绘图)
    # ----------------------------
    # Spark 也是无法直接画图的，需要将数据 collect 到 Driver 端用 Matplotlib 画
    # 关键点：为了防止 OOM，这里进行采样 (例如 10%) 或者只取需要的列
    print("--- Generating Boxplots ---")

    # 采样 10% 的数据用于画图，足以反映分布情况，且不会撑爆内存
    plot_df = df.select("volume", "speed").sample(fraction=0.1, seed=42).toPandas()

    output_dir = '/app/preprocess'
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    for col_name in ['volume', 'speed']:
        plt.figure()
        # pandas boxplot 需要过滤掉 None 值否则可能报错
        plot_df.boxplot(column=col_name)
        plt.title(f'{col_name} 异常值 (Sampled)')
        path_fig = os.path.join(output_dir, f'fig-{col_name}-exception-spark.png')
        print('saving figure to:', os.path.abspath(path_fig))
        plt.savefig(path_fig, dpi=150, bbox_inches='tight')
        plt.close()

    # ----------------------------
    # 3. 数据清洗
    # ----------------------------
    print("--- Cleaning Data ---")

    # A. 删除 road_seg_id 缺失的行
    df_clean = df.na.drop(subset=["road_seg_id"])

    # B. 前向填充 (Forward Fill)
    # 在 Spark 中，ffill 需要使用 Window 函数
    # 定义窗口：按 road_seg_id 分组，按时间排序
    window_spec = Window.partitionBy("road_seg_id").orderBy("data_time")

    # last(col, ignorenulls=True) 实现了 ffill 的逻辑
    df_clean = df_clean.withColumn("data_time", last("data_time", ignorenulls=True).over(window_spec)) \
                       .withColumn("volume", last("volume", ignorenulls=True).over(window_spec)) \
                       .withColumn("speed", last("speed", ignorenulls=True).over(window_spec))

    # C. 删除剩余的空值 (对应 df.dropna())
    df_clean = df_clean.na.drop()

    # D. 异常值截断 (Outlier Clipping)
    # 计算 IQR 需要 approxQuantile (近似分位数，效率远高于精确分位数)
    for col_name in ['volume', 'speed']:
        # 计算 Q1(0.25) 和 Q3(0.75)
        quantiles = df_clean.approxQuantile(col_name, [0.25, 0.75], 0.01) # 0.01 是相对误差
        Q1, Q3 = quantiles[0], quantiles[1]
        IQR = Q3 - Q1
        lower_bound = Q1 - 1.5 * IQR
        upper_bound = Q3 + 1.5 * IQR

        # 使用 when().otherwise() 进行截断 (clip)
        df_clean = df_clean.withColumn(
            col_name,
            when(col(col_name) < lower_bound, lower_bound)
            .when(col(col_name) > upper_bound, upper_bound)
            .otherwise(col(col_name))
        )

    # ----------------------------
    # 4. 数据扩增与 HBase 写入
    # ----------------------------
    print("--- Augmenting and Writing to HBase ---")

    # 定义写入 HBase 的函数 (将 DataFrame 转换为符合 HBase Catalog 的格式并写入)
    def write_to_hbase(dataframe):
        # 1. 计算 salt
        # 2. 格式化时间字符串 (yyyy-MM-ddTHH:mm:ss) 对应原代码 strftime('%Y-%m-%dT%H:%M:%S')
        # 3. 拼接 rowkey: data_time|salt|road_seg_id
        # 4. 类型转换：HBase 通常存字符串，或者由 Connector 处理，这里显式转为 String 以匹配 catalog

        final_df = dataframe.withColumn("salt", salt_udf(col("road_seg_id"))) \
            .withColumn("time_str", date_format(col("data_time"), "yyyy-MM-dd'T'HH:mm:ss")) \
            .withColumn("rowkey", F.concat_ws("|", col("time_str"), col("salt"), col("road_seg_id"))) \
            .withColumn("volume", col("volume").cast("string")) \
            .withColumn("speed", col("speed").cast("string")) \
            .select("rowkey", "volume", "speed")

        final_df.write \
            .format("org.apache.spark.sql.execution.datasources.hbase") \
            .options(catalog=write_catalog) \
            .option("hbase.spark.use.hbasecontext", False) \
            .mode("append") \
            .save()

    # 4.1 写入原始数据
    print("Writing original data...")
    write_to_hbase(df_clean)

    # 4.2 数据扩增 (循环 5 次)
    # 原逻辑：复制 df -> 修改时间 (+i*2 days) -> 添加噪音 -> 写入
    for i in range(1, 6):
        print(f"Augmenting data round {i}...")

        # 增加天数 (data_time + i*2 days)
        # 添加噪音 (uniform(0.95, 1.05)) -> Spark 中用 rand()
        # rand() 生成 [0.0, 1.0)，我们需要映射到 [0.95, 1.05)
        # 0.95 + rand() * (1.05 - 0.95) = 0.95 + rand() * 0.1

        aug_df = df_clean \
            .withColumn("data_time", F.date_add(col("data_time"), i * 2)) \
            .withColumn("data_time", F.to_timestamp(col("data_time"))) \
            .withColumn("volume", col("volume") * (0.95 + F.rand() * 0.1)) \
            .withColumn("speed", col("speed") * (0.95 + F.rand() * 0.1))

        write_to_hbase(aug_df)

    print("--- Done ---")
    spark.stop()

if __name__ == "__main__":
    main()
