
"""
Data preprocessing and augmentation for traffic dataset
"""

import os.path
import numpy as np
import pandas as pd
import matplotlib
# 强制使用不需要显示前端的后端（通常在导入pyplot前设置）
matplotlib.use('Agg')  # 'Agg' 后端渲染PNG, 'PDF' 后端渲染PDF, 'SVG' 后端渲染SVG
import matplotlib.pyplot as plt
import psycopg2
from datetime import timedelta
import happybase
import hashlib

HBASE_TBL_NAME = 'traffic_data'
HBASE_COLFAM_NAME = 'info'

summary_sql = """
SELECT
    COUNT(*) as count_line,
    COUNT(DISTINCT road_seg_id) as count_site,
    MIN(data_time) as min_time,
    MAX(data_time) as max_time,
    AVG(volume) as avg_volume,
    AVG(speed) as avg_speed
FROM raw_traffic_data;
"""

missing_sql = """
SELECT
    SUM(CASE WHEN volume IS NULL THEN 1 ELSE 0 END) as volume_null,
    SUM(CASE WHEN speed IS NULL THEN 1 ELSE 0 END) as speed_null,
    SUM(CASE WHEN road_seg_id IS NULL THEN 1 ELSE 0 END) as id_null
FROM raw_traffic_data;
"""

# ----------------------------
# 1. 数据库连接，读取数据，打印概况
# ----------------------------
with psycopg2.connect(
        host="postgres",
        database="traffic_db",
        user="postgres",
        password="postgres"
    ) as pg_conn:

    df = pd.read_sql_query("SELECT * FROM raw_traffic_data", pg_conn)
    print(pd.read_sql_query(summary_sql, pg_conn))
    print(pd.read_sql_query(missing_sql, pg_conn))
    print(df.describe(include='all'))

# ----------------------------
# 2. 异常检测
# ----------------------------
# 绘制箱线图
for col in ['volume', 'speed']:
    plt.figure()
    df.boxplot(column=col)
    plt.title(f'{col} 异常值')
    path_fig = os.path.join(
        '/app',
        'preprocess',
        f'fig-{col}-exception.png',
    )
    print('saving figure to:', os.path.abspath(path_fig))
    plt.savefig(path_fig, dpi=150, bbox_inches='tight')
    plt.close()

# ----------------------------
# 3. 数据清洗
# ----------------------------
# road_seg_id 缺失直接删除
df = df.dropna(subset=['road_seg_id'])
# 前向填充
df = df.sort_values(['road_seg_id', 'data_time'])
df[['data_time', 'volume', 'speed']] = df[['data_time', 'volume', 'speed']].ffill()
df = df.dropna()

# 异常值截断
for col in ['volume', 'speed']:
    Q1, Q3 = df[col].quantile([0.25, 0.75])
    R = 1.5 * (Q3 - Q1)
    df[col] = df[col].clip(Q1 - R, Q3 + R)

# ----------------------------
# 4. 连接HBASE，数据扩增，向HBASE写入数据
# ----------------------------
df['data_time'] = pd.to_datetime(df['data_time'])

def salt(road_id: str, salt_num: int = 10):
    return str(int(hashlib.md5(road_id.encode('utf-8')).hexdigest(), 16) % salt_num)

connection_pool = happybase.ConnectionPool(size=5, host='hbase')
with connection_pool.connection() as hbase_conn:
    table = hbase_conn.table('traffic_data')
    bHBASE_COLFAM_NAME = HBASE_COLFAM_NAME.encode('utf-8')
    with table.batch(batch_size=1000) as batch:

        def write_dataframe(dataframe: pd.DataFrame):
            for _, r in dataframe.iterrows():
                road_seg_id = r['road_seg_id']
                road_seg_id_salt = salt(road_seg_id)
                data_time = r['data_time'].strftime('%Y-%m-%dT%H:%M:%S')
                # 行键设计
                #   1. 为什么 road_seg_id 加盐可以防止热点问题？
                #       HBase 的默认行为是把数据按 RowKey 的字典顺序（ASCII ordering）进行排序，
                #       并切分成多个 Region（分区） 分布在不同的服务器（RegionServer）上。
                #       在未加盐的情况下，如果 road_seg_id 是有规律（取值是递增的）的，
                #       那么在向 HBase 写入数据时，可能会把所有数据都写入 Region A。
                #       “加盐”就是在 RowKey 前面拼上一个随机数或 Hash 值，强制打乱排序。
                #   2. 怎样的 road_seg_id 容易产生热点问题？
                #       如果 road_seg_id 是像 001, 002 这样的递增数字，
                #       或者 road_seg_id 是像 BJ_Road_183, BJ_Road_698 这样带有固定前缀的字符串，
                #       那么就极有可能产生热点问题。
                #       另外，如果场景是通过 CSV 导入数据进行批量离线处理，
                #       那么在不加盐的情况下，导入速度就会特别慢。
                #       如果 road_seg_id 是 UUID 这类本就无序的数据，那么就不必加盐。
                #       如果导入的数据量极小，单台服务器足够处理，那就不必加盐。
                rk = f"{data_time}|{road_seg_id_salt}|{road_seg_id}"
                batch.put(
                    rk,
                    {
                        # 在写入 HBase 数据库时，不必插入 road_seg_id 和 data_time 两列，
                        # 是因为它们已经出现在行键里了，在下游读取数据时只需 split 行键即可提取相应字段数据
                        bHBASE_COLFAM_NAME + b':volume':
                            str(r['volume']).encode('utf-8'),
                        bHBASE_COLFAM_NAME + b':speed':
                            str(r['speed']).encode('utf-8'),
                    }
                )

        write_dataframe(df)
        for i in range(1, 6):
            tmp = df.copy()
            # 修改日期
            tmp['data_time'] += timedelta(days=i*2)
            # 添加噪音
            tmp['volume'] *= np.random.uniform(0.95, 1.05, len(tmp))
            tmp['speed'] *= np.random.uniform(0.95, 1.05, len(tmp))
            write_dataframe(tmp)
