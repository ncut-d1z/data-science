"""
Load traffic data from HBase into Spark DataFrame

通过 Spark–HBase Connector 直接扫描 HBase 表，
解析盐化行键，恢复出监测点 ID 与时间戳，
并构造结构化 DataFrame 供后续查询、聚合与分析使用。
"""

from pyspark.sql import SparkSession
from pyspark.sql.types import (
    StructType, StructField,
    StringType, IntegerType, DoubleType, TimestampType
)
from pyspark.sql.functions import split, col, to_timestamp
from pyspark.sql import DataFrame


def load_hbase_dataframe(spark: SparkSession):
    """
    从 HBase 读取 traffic_data 表，解析行键和列族数据，返回 Spark DataFrame
    """

    catalog = """
    {
      "table":{"namespace":"default","name":"traffic_data"},
      "rowkey":"key",
      "columns":{
        "rowkey":{"cf":"rowkey","col":"key","type":"string"},
        "volume":{"cf":"info","col":"volume","type":"string"},
        "speed":{"cf":"info","col":"speed","type":"string"}
      }
    }
    """

    df_raw: DataFrame = spark.read \
        .options(catalog=catalog) \
        .format("org.apache.spark.sql.execution.datasources.hbase") \
        .load()

    # 行键格式: data_time|road_seg_id_salt|road_seg_id
    # 例如: 2023-01-01T10:30:00|3|BJ_Road_123
    df = (
        df_raw
        .withColumn("rk_parts", split(col("rowkey"), "\\|"))
        .withColumns({
            "data_time_str": col("rk_parts")[0],  # 提取时间字符串
            "road_seg_id": col("rk_parts")[2],  # 提取道路段ID
            "volume": col("volume").cast("double"),
            "speed": col("speed").cast("double"),
        })
        # https://spark.apache.org/docs/latest/api/python/reference/pyspark.sql/api/pyspark.sql.functions.to_timestamp.html
        # https://spark.apache.org/docs/latest/sql-ref-datetime-pattern.html
        .withColumn("data_time", to_timestamp(col("data_time_str"), "yyyy-MM-dd'T'HH:mm:ss"))  # 转换时间格式
        # 要么用 `.drop("rk_parts", "data_time_str", "rowkey")` 删除临时列
        # 要么用 `.select("road_seg_id", "data_time", "volume", "speed")` 选择需要的列
        # 这两种方法都会返回一个新的 DataFrame
        .select("road_seg_id", "data_time", "volume", "speed")
    )

    return df
