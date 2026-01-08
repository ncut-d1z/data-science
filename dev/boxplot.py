#!/usr/bin/env python
# -*- coding: utf-8 -*-

"""
Data preprocessing and augmentation for traffic dataset
"""

import os
import os.path
import pandas as pd
import matplotlib
# 强制使用不需要显示前端的后端（在导入pyplot前设置）
matplotlib.use('Agg')  # 'Agg' 后端渲染PNG, 'PDF' 后端渲染PDF, 'SVG' 后端渲染SVG
import matplotlib.pyplot as plt
from sqlalchemy import create_engine  # 导入 SQLAlchemy


def boxplot():
    # 构建 SQLAlchemy 连接字符串
    #       格式: postgresql://username:password@host:port/database
    db_url = "postgresql://postgres:postgres@localhost:5432/traffic_db"

    # 创建数据库引擎
    engine = create_engine(db_url)

    # 直接传入 engine 读取数据
    try:
        df = pd.read_sql_query("SELECT * FROM raw_traffic_data", engine)
    except Exception as e:
        print(f"Error reading from database: {e}")
        return

    # 确保结果目录存在，防止 savefig 报错
    os.makedirs('result', exist_ok=True)

    # 绘制箱线图
    for col in ['volume', 'speed']:
        if col not in df.columns:
            print(f"Warning: Column {col} not found in data.")
            continue

        plt.figure()
        # 简单的异常值处理或绘图
        df.boxplot(column=col)
        plt.title(f'Boxplot {col}')

        path_fig = os.path.join(
            'result',
            f'fig-{col}-exception.png',
        )

        print('saving figure to:', os.path.abspath(path_fig))
        plt.savefig(path_fig, dpi=150, bbox_inches='tight')
        plt.close()


if __name__ == '__main__':
    boxplot()
