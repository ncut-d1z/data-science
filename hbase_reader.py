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

def load_hbase_dataframe(spark: SparkSession):
    """
    从 HBase 读取 traffic 表，解析行键和列族数据，返回 Spark DataFrame
    """

    catalog = """
    {
      "table":{"namespace":"default","name":"traffic"},
      "rowkey":"key",
      "columns":{
        "rowkey":{"cf":"rowkey","col":"key","type":"string"},
        "volume":{"cf":"d","col":"volume","type":"int"},
        "speed":{"cf":"d","col":"speed","type":"double"}
      }
    }
    """

    df_raw = spark.read \
        .options(catalog=catalog) \
        .format("org.apache.spark.sql.execution.datasources.hbase") \
        .load()

    # rowkey: salt_roadSegId_dataTime
    df = (
        df_raw
        .withColumn("rk_parts", split(col("rowkey"), "_"))
        .withColumn("road_seg_id", col("rk_parts")[1])
        .withColumn("data_time", to_timestamp(col("rk_parts")[2]))
        .drop("rk_parts", "rowkey")
    )

    return df
