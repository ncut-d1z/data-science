#!/bin/bash

# 确保脚本在遇到严重错误时继续执行（我们希望尽可能多地停止服务）
set +e

# 加载环境变量
source env.sh

echo "========================================================"
echo "正在停止大数据生态圈服务..."
echo "========================================================"

# --- 1. 优雅停止阶段 (Graceful Shutdown) ---

echo "[INFO] 停止 HBase 相关服务..."
# 停止 REST 和 Thrift
su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh stop rest" >/dev/null 2>&1
su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh stop thrift" >/dev/null 2>&1
# 停止 HBase 集群
su -s /bin/bash hbase -c "$HBASE_HOME/bin/stop-hbase.sh" &
# stop-hbase.sh 有时会卡住，设置一个等待超时，可以在后台运行
HBASE_PID=$!
sleep 10
# 如果还在运行，就不管它了，后面会强制杀

echo "[INFO] 停止 Hadoop 相关服务..."
# 停止 JobHistoryServer
su - hadoop -c "$HADOOP_HOME/bin/mapred --daemon stop historyserver" >/dev/null 2>&1
# 停止 Hadoop 集群 (YARN + HDFS)
su - hadoop -c "$HADOOP_HOME/sbin/stop-all.sh"

echo "[INFO] 停止 ZooKeeper..."
su -s /bin/bash zookeeper -c "$ZOOKEEPER_HOME/bin/zkServer.sh stop"

echo "[INFO] 等待 5 秒让进程退出..."
sleep 5

# --- 2. 强制清理阶段 (Force Kill) ---

echo "========================================================"
echo "检查并清理残留进程..."
echo "========================================================"

# 定义需要查杀的进程关键字列表
TARGET_PROCESSES=(
    "HMaster"
    "HRegionServer"
    "ThriftServer"
    "RESTServer"
    "NameNode"
    "DataNode"
    "ResourceManager"
    "NodeManager"
    "JobHistoryServer"
    "QuorumPeerMain"
    "RunJar" # 停止正在运行的 MapReduce/Spark 任务容器
    "CoarseGrainedExecutorBackend" # Spark Executor
    "SparkSubmit"
)

# 遍历并强制杀掉
for proc in "${TARGET_PROCESSES[@]}"; do
    # 获取进程ID (排除 grep 自身)
    pids=$(pgrep -f "$proc")

    if [ -n "$pids" ]; then
        echo "[WARN] 发现残留进程 $proc (PIDs: $pids)，正在强制终止..."
        # 将换行符转换为空格以便 kill 命令处理
        echo "$pids" | xargs kill -9
    else
        echo "[OK] $proc 已停止"
    fi
done

# --- 3. 清理 "process information unavailable" ---

echo "========================================================"
echo "清理 JVM 临时文件 (修复 jps unavailable 问题)..."
echo "========================================================"

# jps 出现 "process information unavailable" 通常是因为 /tmp/hsperfdata_<user> 目录下的 PID 文件残留
# 清理 hadoop, hbase, zookeeper, spark 以及 root 用户的 hsperfdata
rm -rf /tmp/hsperfdata_hadoop
rm -rf /tmp/hsperfdata_hbase
rm -rf /tmp/hsperfdata_zookeeper
rm -rf /tmp/hsperfdata_spark
rm -rf /tmp/hsperfdata_root

# 也可以通配符清理，但要小心误删其他用户的
# rm -rf /tmp/hsperfdata_*

echo "[OK] 清理完成。"

# --- 4. 最终状态检查 ---

echo "========================================================"
echo "当前系统 Java 进程状态 (JPS):"
echo "========================================================"
jps

echo "--------------------------------------------------------"
echo "如果上面除了 Jps 之外没有其他进程，说明停止成功。"
echo "========================================================"
