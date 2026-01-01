#!/bin/bash

# 加载环境变量
if [ -f "env.sh" ]; then
    source env.sh
else
    echo "错误: 找不到 env.sh 文件，无法加载路径变量。"
    exit 1
fi

echo "========================================================"
echo "开始清理大数据组件日志文件..."
echo "注意：请确保所有服务已停止，否则可能导致文件句柄未释放。"
echo "========================================================"

# 定义清理函数
clean_dir() {
    local dir=$1
    if [ -d "$dir" ]; then
        # 检查目录下是否有文件，避免 rm 报错
        if [ "$(ls -A $dir)" ]; then
            rm -rf "${dir:?}"/*
            echo "[OK] 已清空: $dir"
        else
            echo "[INFO] 目录为空，无需清理: $dir"
        fi
    else
        echo "[WARN] 目录不存在，跳过: $dir"
    fi
}

echo "--- 清理 Hadoop ---"
clean_dir "$HADOOP_LOG"
clean_dir "$HADOOP_HOME/logs/userlogs"

echo "--- 清理 HBase ---"
clean_dir "$HBASE_HOME/logs"
clean_dir "/home/hbase/logs"

echo "--- 清理 ZooKeeper ---"
clean_dir "$ZOO_LOG_DIR"
if [ -f "$ZOOKEEPER_HOME/zookeeper.out" ]; then
    rm -f "$ZOOKEEPER_HOME/zookeeper.out"
    echo "[OK] 已删除: $ZOOKEEPER_HOME/zookeeper.out"
fi

echo "========================================================"
echo "所有日志已清理完毕。"
echo "现在启动服务将从零开始记录日志。"
echo "========================================================"
