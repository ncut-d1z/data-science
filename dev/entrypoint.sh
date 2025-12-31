#!/bin/bash

source env.sh

set -eux

# 启动 SSH（该任务只能由 root 用户执行）
if ! systemctl is-active --quiet ssh; then
    service ssh start
    echo "SSH 服务已启动"
else
    echo "SSH 服务已启用，跳过启动"
fi
# 预期输出：
#    * Starting OpenBSD Secure Shell server sshd

# 格式化 HDFS（该任务只能由 root 用户执行）
# 注意：仅当 NameNode 数据目录不存在或为空时才格式化

# su - hbase -c "stop-hbase.sh"
# su - zookeeper -c "echo 'deleteall /hbase' | /opt/zookeeper/bin/zkCli.sh -server localhost:2181"
# su - hadoop -c "hdfs dfs -rm -r /hbase"

rm -rf ${HADOOP_NAMENODE} ${HADOOP_DATANODE} ${HADOOP_TEMP} ${ZOO_DAT_DIR} ${ZOO_LOG_DIR}
mkdir -p ${HADOOP_NAMENODE} ${HADOOP_DATANODE} ${HADOOP_TEMP} ${ZOO_DAT_DIR} ${ZOO_LOG_DIR}
chown -R hadoop:hadoop ${HADOOP_NAMENODE} ${HADOOP_DATANODE} ${HADOOP_TEMP}
chown -R zookeeper:zookeeper ${ZOO_DAT_DIR} ${ZOO_LOG_DIR}

hdfs namenode -format
# 日志中如果有
#       INFO common.Storage: Storage directory /opt/hadoop/data/dfs/namenode has been successfully formatted.
# 就说明格式化动作成功了
echo "HDFS 已格式化"
# 本来是因为 hadoop 无法调用 hdfs，所以才以 root 用户的身份调用了 hdfs 命令，
# 后来却发现 hdfs 命令会产生归属于 root 用户的文件，使得 hadoop 用户无法使用，
# 因此必须递归地改变 $HADOOP_NAMENODE 目录下所有文件的权属
chown -R hadoop:hadoop $HADOOP_NAMENODE

# 检查 Hadoop 是否已在运行（通过 jps 判断关键进程）
hadoop_running=false
if pgrep -f "NameNode" > /dev/null && pgrep -f "DataNode" > /dev/null && pgrep -f "ResourceManager" > /dev/null; then
    hadoop_running=true
fi

if [ "$hadoop_running" = false ]; then
    # 检查是否有残留进程（残缺状态）
    if pgrep -f "org.apache.hadoop" > /dev/null; then
        echo "检测到 Hadoop 残留进程，正在清理..."
        su - hadoop -c "$HADOOP_HOME/sbin/stop-all.sh" || true
        sleep 5
        pkill -f "org.apache.hadoop" || true
        sleep 3
    fi
    su - hadoop -c "$HADOOP_HOME/sbin/start-all.sh"
    echo "Hadoop 服务已启动"

    su - hadoop -c "$HADOOP_HOME/bin/mapred --daemon start historyserver"
    # jps | grep JobHistoryServer
    echo "Hadoop 日志已启动"
else
    echo "Hadoop 服务已在运行，跳过启动"
fi
# 预期输出：
#   WARNING: Attempting to start all Apache Hadoop daemons as hadoop in 10 seconds.
#   WARNING: This is not a recommended production deployment configuration.
#   WARNING: Use CTRL-C to abort.
#   Starting namenodes on [localhost]
#   localhost: Warning: Permanently added 'localhost' (ED25519) to the list of known hosts.
#   Starting datanodes
#   Starting secondary namenodes [67ef3b3bee62]
#   67ef3b3bee62: Warning: Permanently added '67ef3b3bee62' (ED25519) to the list of known hosts.
#   Starting resourcemanager
#   resourcemanager is running as process 446.  Stop it first and ensure /tmp/hadoop-hadoop-resourcemanager.pid file is empty before retry.
#   Starting nodemanagers

# 检查 NameNode 是否在线
if ! jps | grep -q NameNode; then
    echo "NameNode 不在线！"
    tail -n 20 $HADOOP_LOG/hadoop-hadoop-namenode-$(uname -n).log
    # 如果需要重新启动，就运行 `su - hadoop -c "$HADOOP_HOME/sbin/stop-all.sh"`
    exit 1
fi
# 如果 NameNode 不在线，就运行 `tail -n 20 $HADOOP_LOG/hadoop-hadoop-namenode-$(uname -n).log` 命令检查日志
# 如果需要重新启动，就运行 `su - hadoop -c "$HADOOP_HOME/sbin/stop-all.sh"`

# 检查 DataNode 是否在线
if ! jps | grep -q DataNode; then
    echo "DataNode 不在线！"
    exit 1
fi
# 如果 DataNode 不在线，就运行 `tail -n 20 $HADOOP_LOG/hadoop-hadoop-datanode-$(uname -n).log` 命令检查日志

# 检查 ZooKeeper 是否已在运行
zk_running=false
if jps | grep -q QuorumPeerMain; then
    zk_running=true
fi

if [ "$zk_running" = false ]; then
    # 检查是否有残留进程
    if pgrep -f "QuorumPeerMain" > /dev/null; then
        echo "检测到 ZooKeeper 残留进程，正在清理..."
        su -s /bin/bash zookeeper -c "$ZOOKEEPER_HOME/bin/zkServer.sh stop" || true
        sleep 3
        pkill -f "QuorumPeerMain" || true
        sleep 2
    fi
    su -s /bin/bash zookeeper -c "$ZOOKEEPER_HOME/bin/zkServer.sh start"
    # 如果遇到故障（例如找不到进程、看不见端口），看不到日志，那就改为运行：
    #   su -s /bin/bash zookeeper -c "$ZOOKEEPER_HOME/bin/zkServer.sh start-foreground"
    echo "ZooKeeper 服务已启动"
else
    echo "ZooKeeper 服务已在运行，跳过启动"
fi
# 预期输出：
#   ZooKeeper JMX enabled by default
#   Using config: /opt/zookeeper/bin/../conf/zoo.cfg
#   Starting zookeeper ... STARTED

# 检查 Zookeeper 是否在线
if ! jps | grep -q QuorumPeerMain; then
    echo "ZooKeeper 启动失败！"
    # 检查日志： vi /home/zookeeper/logs/zookeeper-zookeeper-server-$(uname -n).out
    exit 1
fi
# 检查日志： vi /home/zookeeper/logs/zookeeper-zookeeper-server-$(uname -n).out

# 为 HBase 准备磁盘空间
# 先检查 /hbase 是否已存在
hbase_dir_exists=$(su - hadoop -c "hdfs dfs -test -e /hbase && echo yes || echo no")
if [ "$hbase_dir_exists" = "no" ]; then
    # 递归创建目录
    su - hadoop -c "hdfs dfs -mkdir -p /hbase"
    # 将该目录及其父目录的权限交给 hbase（最内层必须给 hbase）
    su - hadoop -c "hdfs dfs -chown -R hbase:hbase /hbase"
    echo "/hbase 目录已创建并授权"
else
    echo "/hbase 目录已存在，跳过创建"
fi
# 登出 hadoop 用户（su - 在子 shell 中执行，无需显式 exit）

# 检查 HBase 是否已在运行
hbase_running=false
if jps | grep -q HMaster && jps | grep -q HRegionServer; then
    hbase_running=true
fi

if [ "$hbase_running" = false ]; then
    # 检查残留
    if pgrep -f "org.apache.hadoop.hbase" > /dev/null; then
        echo "检测到 HBase 残留进程，正在清理..."
        su -s /bin/bash hbase -c "$HBASE_HOME/bin/stop-hbase.sh" || true
        sleep 5
        pkill -f "org.apache.hadoop.hbase" || true
        sleep 3
    fi
    su -s /bin/bash hbase -c "$HBASE_HOME/bin/start-hbase.sh"
    echo "HBase 服务已启动"
else
    echo "HBase 服务已在运行，跳过启动"
fi
# 预期输出：
#   SLF4J: Class path contains multiple SLF4J bindings.
#   SLF4J: Found binding in [jar:file:/opt/hbase/lib/client-facing-thirdparty/log4j-slf4j-impl-2.17.2.jar!/org/slf4j/impl/StaticLoggerBinder.class]
#   SLF4J: Found binding in [jar:file:/opt/hadoop/share/hadoop/common/lib/slf4j-reload4j-1.7.36.jar!/org/slf4j/impl/StaticLoggerBinder.class]
#   SLF4J: See http://www.slf4j.org/codes.html#multiple_bindings for an explanation.
#   SLF4J: Actual binding is of type [org.apache.logging.slf4j.Log4jLoggerFactory]
#   running master, logging to /opt/hbase/logs/hbase-hbase-master-c5cfad4f139d.out

# su -s /bin/bash hbase -c "$HBASE_HOME/bin/stop-hbase.sh"

# 检查 HRegionServer 是否在线
if ! jps | grep -q HRegionServer; then
    echo "HRegionServer 未启动！"
    # 检查日志： vi $HBASE_HOME/logs/hbase-hbase-regionserver-$(uname -n).log
    # 检查日志： vi $HBASE_HOME/logs/hbase-hbase-regionserver-$(uname -n).out
    exit 1
fi

# 检查 HMaster 是否在线
if ! jps | grep -q HMaster; then
    echo "HMaster 未启动！"
    # 检查日志： vi $HBASE_HOME/logs/hbase-hbase-master-$(uname -n).log
    # 检查日志： vi $HBASE_HOME/logs/hbase-hbase-master-$(uname -n).out
    exit 1
fi

# 停止 HBase 服务
# su -s /bin/bash hbase -c "$HBASE_HOME/bin/stop-hbase.sh"

# 检查 Thrift 是否已在运行
thrift_running=false
if jps | grep -q ThriftServer; then
    thrift_running=true
fi

if [ "$thrift_running" = false ]; then
    # 检查残留
    if pgrep -f "ThriftServer" > /dev/null; then
        su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh stop thrift" || true
        sleep 2
        pkill -f ThriftServer || true
        sleep 1
    fi
    su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh start thrift"
    echo "HBase Thrift 服务器已启动"
else
    echo "HBase Thrift 服务器已在运行，跳过启动"
fi
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-thrift-$(uname -n).out

# 检查 REST 是否已在运行
rest_running=false
if jps | grep -q RESTServer; then
    rest_running=true
fi

if [ "$rest_running" = false ]; then
    # 检查残留
    if pgrep -f "RESTServer" > /dev/null; then
        su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh stop rest" || true
        sleep 2
        pkill -f RESTServer || true
        sleep 1
    fi
    su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh start rest"
    echo "HBase REST 服务器已启动"
else
    echo "HBase REST 服务器已在运行，跳过启动"
fi
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-rest-$(uname -n).out

# 为 HBase 建表（该任务只能由 root 用户执行）
# 先确保 HMaster 在线
if ! jps | grep -q HMaster; then
    echo "HMaster 未运行，无法建表"
    exit 1
fi

sleep 60  # 等待 HBase 初始化

echo "create 'traffic_data', 'info'" | hbase shell
echo "create 'traffic_agg_15min', 'info'" | hbase shell
# 如果建表时，报错：
#   ERROR: KeeperErrorCode = NoNode for /hbase/master
# 那就执行
#   jps | grep HMaster
#   vi $HBASE_HOME/logs/hbase-hbase-master-$(uname -n).log

echo "HBase 建表工作完成"
