
"""
Data preprocessing and augmentation for traffic dataset
"""

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
    plt.savefig(f'fig-{col}-exception.png', dpi=150, bbox_inches='tight')
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

def salt(road_id, salt_num=10):
    return str(int(hashlib.md5(road_id.encode()).hexdigest(), 16) % salt_num)

connection_pool = happybase.ConnectionPool(size=5, host='hbase')
with connection_pool.connection() as hbase_conn:
    table = hbase_conn.table('traffic')
    with table.batch(batch_size=1000) as batch:

        def write_dataframe(dataframe):
            for _, r in dataframe.iterrows():
                rk = f"{salt(r['road_seg_id'])}_{r['road_seg_id']}_{r['data_time']}"
                batch.put(rk, {
                    b'd:volume': str(r['volume']).encode(),
                    b'd:speed': str(r['speed']).encode()
                })

        write_dataframe(df)
        for i in range(1, 6):
            tmp = df.copy()
            # 修改日期
            tmp['data_time'] += timedelta(days=i*2)
            # 添加噪音
            tmp['volume'] *= np.random.uniform(0.95, 1.05, len(tmp))
            tmp['speed'] *= np.random.uniform(0.95, 1.05, len(tmp))
            write_dataframe(tmp)
