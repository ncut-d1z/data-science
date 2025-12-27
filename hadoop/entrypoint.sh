#!/bin/bash

set -eux

# 启动 SSH（该任务只能由 root 用户执行）
service ssh start
echo "SSH 服务已启动"
# 预期输出：
#    * Starting OpenBSD Secure Shell server sshd

# 格式化 HDFS（该任务只能由 root 用户执行）
hdfs namenode -format
# 日志中如果有
#       INFO common.Storage: Storage directory /opt/hadoop/data/dfs/namenode has been successfully formatted.
# 就说明格式化动作成功了
echo "HDFS 已格式化"
# 本来是因为 hadoop 无法调用 hdfs，所以才以 root 用户的身份调用了 hdfs 命令，
# 后来却发现 hdfs 命令会产生归属于 root 用户的文件，使得 hadoop 用户无法使用，
# 因此必须递归地改变 $HADOOP_NAMENODE 目录下所有文件的权属
chown -R hadoop:hadoop $HADOOP_NAMENODE

su - hadoop -c "$HADOOP_HOME/sbin/start-all.sh"
echo "Hadoop 服务已启动"
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
jps | grep NameNode
# 如果 NameNode 不在线，就运行 `tail -n 20 $HADOOP_LOG/hadoop-hadoop-namenode-$(uname -n).log` 命令检查日志
# 如果需要重新启动，就运行 `su - hadoop -c "$HADOOP_HOME/sbin/stop-all.sh"`

# 检查 DataNode 是否在线
jps | grep DataNode

su -s /bin/bash zookeeper -c "$ZOOKEEPER_HOME/bin/zkServer.sh start"
# 如果遇到故障（例如找不到进程、看不见端口），看不到日志，那就改为运行：
#   su -s /bin/bash zookeeper -c "$ZOOKEEPER_HOME/bin/zkServer.sh start-foreground"
# 预期输出：
#   ZooKeeper JMX enabled by default
#   Using config: /opt/zookeeper/bin/../conf/zoo.cfg
#   Starting zookeeper ... STARTED

# 检查 Zookeeper 是否在线
jps | grep QuorumPeerMain
# 检查日志： vi /home/zookeeper/logs/zookeeper-zookeeper-server-$(uname -n).out

# 为 HBase 准备磁盘空间
su - hadoop
# 递归创建目录
hdfs dfs -mkdir -p /hbase
# 将该目录及其父目录的权限交给 hbase（最内层必须给 hbase）
hdfs dfs -chown -R hbase:hbase /hbase
# 登出 hadoop 用户
exit

su -s /bin/bash hbase -c "$HBASE_HOME/bin/start-hbase.sh"
echo "HBase 服务已启动"
# 预期输出：
#   SLF4J: Class path contains multiple SLF4J bindings.
#   SLF4J: Found binding in [jar:file:/opt/hbase/lib/client-facing-thirdparty/log4j-slf4j-impl-2.17.2.jar!/org/slf4j/impl/StaticLoggerBinder.class]
#   SLF4J: Found binding in [jar:file:/opt/hadoop/share/hadoop/common/lib/slf4j-reload4j-1.7.36.jar!/org/slf4j/impl/StaticLoggerBinder.class]
#   SLF4J: See http://www.slf4j.org/codes.html#multiple_bindings for an explanation.
#   SLF4J: Actual binding is of type [org.apache.logging.slf4j.Log4jLoggerFactory]
#   running master, logging to /opt/hbase/logs/hbase-hbase-master-c5cfad4f139d.out

jps | grep HRegionServer
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-regionserver-$(uname -n).log
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-regionserver-$(uname -n).out

jps | grep HMaster
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-master-$(uname -n).log
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-master-$(uname -n).out

# 停止 HBase 服务
# su -s /bin/bash hbase -c "$HBASE_HOME/bin/stop-hbase.sh"

su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh start thrift"
echo "HBase Thrift 服务器已启动"
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-thrift-$(uname -n).out

jps | grep ThriftServer

su -s /bin/bash hbase -c "$HBASE_HOME/bin/hbase-daemon.sh start rest"
echo "HBase REST 服务器已启动"
# 检查日志： vi $HBASE_HOME/logs/hbase-hbase-rest-$(uname -n).out

jps | grep RESTServer

# 为 HBase 建表（该任务只能由 root 用户执行）
bash /app/hbase_schema.sh
# 如果建表时，报错：
#   ERROR: KeeperErrorCode = NoNode for /hbase/master
# 那就执行
#   jps | grep HMaster
#   vi $HBASE_HOME/logs/hbase-hbase-master-$(uname -n).log

echo "HBase 建表工作完成"

su -s /bin/bash spark -c "$SPARK_HOME/sbin/start-all.sh"
echo "Spark 服务已启动"
# 预期输出：
#   starting org.apache.spark.deploy.master.Master, logging to /opt/spark/logs/spark-spark-org.apache.spark.deploy.master.Master-1-c5cfad4f139d.out
#   localhost: Warning: Permanently added 'localhost' (ED25519) to the list of known hosts.
#   localhost: starting org.apache.spark.deploy.worker.Worker, logging to /opt/spark/logs/spark-spark-org.apache.spark.deploy.worker.Worker-1-c5cfad4f139d.out

# 检查日志： vi $SPARK_HOME/logs/spark-spark-org.apache.spark.deploy.master.Master-1-$(uname -n).out
# 检查日志： vi $SPARK_HOME/logs/spark-spark-org.apache.spark.deploy.worker.Worker-1-$(uname -n).out

su -s /bin/bash spark -c "bash /app/spark_run.sh"
echo "数据分析工作完成"

# 保持容器运行
tail -f /dev/null

# nc -z localhost 2181
