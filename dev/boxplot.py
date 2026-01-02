
"""
Data preprocessing and augmentation for traffic dataset
"""

import os.path
import pandas as pd
import matplotlib
# 强制使用不需要显示前端的后端（通常在导入pyplot前设置）
matplotlib.use('Agg')  # 'Agg' 后端渲染PNG, 'PDF' 后端渲染PDF, 'SVG' 后端渲染SVG
import matplotlib.pyplot as plt
import psycopg2

HBASE_TBL_NAME = 'traffic_data'
HBASE_COLFAM_NAME = 'info'


def boxplot():
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

    # 绘制箱线图
    for col in ['volume', 'speed']:
        plt.figure()
        df.boxplot(column=col)
        plt.title(f'{col} 异常值')
        path_fig = os.path.join(
            'result',
            f'fig-{col}-exception.png',
        )
        print('saving figure to:', os.path.abspath(path_fig))
        plt.savefig(path_fig, dpi=150, bbox_inches='tight')
        plt.close()


if __name__ == '__main__':
    boxplot()
