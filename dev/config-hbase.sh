#!/bin/bash
# 配置脚本（在容器执行）

set -eux  # 遇到错误立即退出

# 创建必要目录并设置权限
mkdir -p \
        /home/hbase/data/hbase \
        /home/hbase/logs
chown -R hbase:hbase /home/hbase/data/hbase
chown -R hbase:hbase /home/hbase/logs
mkdir -p /opt/hbase/logs
chown -R hbase:hbase /opt/hbase/logs

# 在 hbase-env.sh 中显式指定不管理 ZK
echo "export JAVA_HOME=${JAVA_HOME}" >> ${HBASE_CONF}/hbase-env.sh
echo "export HBASE_MANAGES_ZK=false" >> ${HBASE_CONF}/hbase-env.sh
echo "export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true" >> ${HBASE_CONF}/hbase-env.sh

# @see: https://github.com/apache/hbase/blob/master/hbase-common/src/main/resources/hbase-default.xml
cat > "${HBASE_CONF}/hbase-site.xml" << EOF
<?xml version="1.0"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>
  <!-- HBase根目录，使用本地文件系统存储 -->
  <property>
    <name>hbase.rootdir</name>
    <value>hdfs://localhost:9000/hbase</value>
  </property>

  <!-- 启用分布式模式 -->
  <!-- 注意：连接外部独立 ZK 时，通常需要开启分布式模式（即使是伪分布式）-->
  <property>
    <name>hbase.cluster.distributed</name>
    <value>true</value>
  </property>

  <!-- 绑定所有网络接口 -->
  <property>
    <name>hbase.master.hostname</name>
    <value>localhost</value>
  </property>
  <property>
    <name>hbase.regionserver.hostname</name>
    <value>localhost</value>
  </property>
  <property>
    <name>hbase.master.ipc.address</name>
    <value>0.0.0.0</value>
  </property>
  <property>
    <name>hbase.regionserver.ipc.address</name>
    <value>0.0.0.0</value>
  </property>
  <property>
    <name>hbase.master.port</name>
    <value>16000</value>
  </property>
  <property>
    <name>hbase.master.info.port</name>
    <value>16010</value>
  </property>
  <property>
    <name>hbase.regionserver.port</name>
    <value>16201</value>
  </property>
  <property>
    <name>hbase.regionserver.info.port</name>
    <value>16301</value>
  </property>

  <!-- ZooKeeper配置 -->
  <property>
    <name>hbase.zookeeper.property.clientPort</name>
    <value>2181</value>
  </property>
  <property>
    <name>hbase.zookeeper.property.dataDir</name>
    <value>/home/zookeeper</value>
  </property>

  <!-- 关闭集群间复制（单机环境） -->
  <property>
    <name>hbase.zookeeper.quorum</name>
    <value>localhost</value>
  </property>
  <property>
    <name>hbase.replication</name>
    <value>false</value>
  </property>

  <!-- 解决伪分布式下本地文件系统可能出现的流丢失问题 -->
  <property>
    <name>hbase.unsafe.stream.capability.enforce</name>
    <value>false</value>
  </property>
</configuration>
EOF
