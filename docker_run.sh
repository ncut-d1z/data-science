#!/bin/bash
# 一键启动与初始化脚本（在宿主机执行）

set -e

echo "[1/4] 启动 Docker Compose 服务"
docker-compose up -d --build

sleep 15

echo "[2/4] 创建 HBase 表"
docker exec -i hbase bash /app/hbase_schema.sh

echo "[3/4] 数据预处理 + 写入 HBase"
docker exec -i spark python preprocess.py

echo "[4/4] Spark 分析任务"
docker exec -i spark bash spark_run.sh

echo "✅ 全流程执行完成"
