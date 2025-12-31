#!/bin/bash
# 配置脚本（在容器执行）

set -eux  # 遇到错误立即退出

# 创建必要目录并设置权限
mkdir -p ${HADOOP_CONF}
mkdir -p ${HADOOP_LOG}
mkdir -p ${HADOOP_TEMP}
mkdir -p ${HADOOP_DFS}
mkdir -p ${HADOOP_NAMENODE}
mkdir -p ${HADOOP_DATANODE}
chown -R hadoop:hadoop ${HADOOP_CONF}
chown -R hadoop:hadoop ${HADOOP_TEMP}
chown -R hadoop:hadoop ${HADOOP_LOG}
chown -R hadoop:hadoop ${HADOOP_DFS}

cat > "$HADOOP_CONF/hadoop-env.sh" << EOF
export JAVA_HOME=$JAVA_HOME
EOF

cat > "$HADOOP_CONF/core-site.xml" << EOF
<configuration>
    <property>
        <name>fs.defaultFS</name>
        <value>hdfs://localhost:9000</value>
    </property>
    <!-- 指定hadoop运行时产生文件的存储路径 -->
    <property>
        <name>hadoop.tmp.dir</name>
        <!-- 配置到hadoop目录下temp文件夹 -->
        <value>${HADOOP_TEMP}</value>
    </property>
</configuration>
EOF

cat > "$HADOOP_CONF/hdfs-site.xml" << EOF
<configuration>
    <property>
    <!--指定hdfs保存数据副本的数量，包括自己，默认为3-->
    <!--伪分布式模式，此值必须为1-->
        <name>dfs.replication</name>
        <value>1</value>
    </property>
    <property>
        <name>dfs.namenode.http-address</name>
        <!-- name node 存放 name table 的目录 -->
        <value>localhost:50070</value>
    </property>
    <property>
        <name>dfs.namenode.name.dir</name>
        <!-- name node 存放 name table 的目录 -->
        <value>${HADOOP_NAMENODE}</value>
    </property>
    <property>
        <name>dfs.datanode.data.dir</name>
        <!-- data node 存放数据 block 的目录 -->
        <value>${HADOOP_DATANODE}</value>
    </property>
</configuration>
EOF

cat > "$HADOOP_CONF/mapred-site.xml" << EOF
<configuration>
    <property>
        <!--指定mapreduce运行在yarn上-->
        <name>mapreduce.framework.name</name>
        <value>yarn</value>
    </property>
    <property>
        <name>mapred.job.tracker</name>
        <value>hdfs://localhost:9001</value>
    </property>
    <!-- 添加 YARN 容器所需的环境变量 -->
    <property>
        <name>yarn.app.mapreduce.am.env</name>
        <value>HADOOP_MAPRED_HOME=${HADOOP_HOME}</value>
    </property>
    <property>
        <name>mapreduce.map.env</name>
        <value>HADOOP_MAPRED_HOME=${HADOOP_HOME}</value>
    </property>
    <property>
        <name>mapreduce.reduce.env</name>
        <value>HADOOP_MAPRED_HOME=${HADOOP_HOME}</value>
    </property>
</configuration>
EOF

cat > "$HADOOP_CONF/yarn-site.xml" << EOF
<configuration>
    <property>
        <!--NodeManager获取数据的方式-->
        <name>yarn.nodemanager.aux-services</name>
        <value>mapreduce_shuffle</value>
    </property>
    <property>
        <name>yarn.nodemanager.aux-services.mapreduce.shuffle.class</name>
        <value>org.apache.hadoop.mapred.ShuffleHandler</value>
    </property>
    <!-- 显式设置 YARN Classpath，避免环境差异导致找不到类 -->
    <property>
        <name>yarn.application.classpath</name>
        <value>${HADOOP_HOME}/etc/hadoop,${HADOOP_HOME}/share/hadoop/common/*,${HADOOP_HOME}/share/hadoop/common/lib/*,${HADOOP_HOME}/share/hadoop/hdfs/*,${HADOOP_HOME}/share/hadoop/hdfs/lib/*,${HADOOP_HOME}/share/hadoop/mapreduce/*,${HADOOP_HOME}/share/hadoop/mapreduce/lib/*,${HADOOP_HOME}/share/hadoop/yarn/*,${HADOOP_HOME}/share/hadoop/yarn/lib/*</value>
    </property>
    <!-- 禁用虚拟内存检查（防止在资源受限的环境下误杀容器） -->
    <property>
        <name>yarn.nodemanager.vmem-check-enabled</name>
        <value>false</value>
    </property>
</configuration>
EOF

# 配置 SSH
HADOOP_SSH=/home/hadoop/.ssh
# 为 hadoop 用户配置 SSH 无密码登录
mkdir -p $HADOOP_SSH
chmod 700 $HADOOP_SSH
ssh-keygen -t rsa -C 'rsa key for hadoop' -N '' -P '' -f $HADOOP_SSH/id_rsa
cat $HADOOP_SSH/id_rsa.pub >> $HADOOP_SSH/authorized_keys
chmod 600 $HADOOP_SSH/id_rsa
chmod 644 $HADOOP_SSH/id_rsa.pub
chmod 600 $HADOOP_SSH/authorized_keys
chown -R hadoop:hadoop $HADOOP_SSH
