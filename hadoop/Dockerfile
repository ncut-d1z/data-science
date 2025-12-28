FROM ubuntu:22.04

# 设置构建时的环境变量
ARG HADOOP_VERSION
ARG ZOOKEEPER_VERSION
ARG HBASE_VERSION
ARG SPARK_VERSION
ARG SPARK_HADOOP_VERSION

# 配置阿里云镜像源
RUN sed -i 's@http://.*archive.ubuntu.com@http://mirrors.aliyun.com@g' /etc/apt/sources.list && \
    sed -i 's@http://security.ubuntu.com@http://mirrors.aliyun.com@g' /etc/apt/sources.list && \
    apt-get clean && \
    apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y \
        vim \
        bash \
        curl \
        gnupg \
        openjdk-11-jdk \
        tar \
        gcc \
        libpq-dev \
        # 用于数据预处理和分析
        python3 python3-pip python3-dev \
        # 用于 hadoop 跨结点通信
        openssh-server openssh-client \
        # 用于端口检查
        net-tools

RUN mkdir -p /var/run/sshd && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/' /etc/ssh/sshd_config && \
    sed -i 's/#PubkeyAuthentication yes/PubkeyAuthentication yes/' /etc/ssh/sshd_config

ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
# 检查 JDK 是否成功安装
RUN java -version || exit 1
# 检查 Python 和 pip 是否成功安装
RUN python3 -V && python3 -m pip --version || exit 1

RUN mkdir -p /root/.pip && \
    echo '[global]' > /root/.pip/pip.conf && \
    echo 'index-url = https://mirrors.aliyun.com/pypi/simple/' >> /root/.pip/pip.conf && \
    echo 'trusted-host = mirrors.aliyun.com' >> /root/.pip/pip.conf && \
    python3 -m pip install --upgrade pip && \
    python3 -m pip install --no-cache-dir \
        numpy \
        pandas \
        matplotlib \
        psycopg2-binary \
        happybase \
        pyspark

#====================================================

# 下载、安装 Hadoop

ENV HADOOP_TGZ="hadoop-${HADOOP_VERSION}.tar.gz"
ENV HADOOP_URL=https://mirrors.aliyun.com/apache/hadoop/common/hadoop-${HADOOP_VERSION}/${HADOOP_TGZ}
USER root
WORKDIR /tmp
RUN set -eux; \
    curl -f -L -O "${HADOOP_URL}"; \
    # 解压到/opt目录
    tar -xzf ${HADOOP_TGZ} -C /opt; \
    mv /opt/hadoop-${HADOOP_VERSION} /opt/hadoop; \
    # 清理临时文件
    rm -f ${HADOOP_TGZ}*;

# 配置 Hadoop

ENV HADOOP_HOME=/opt/hadoop
ENV HADOOP_CONF=$HADOOP_HOME/etc/hadoop
ENV HADOOP_LOG=$HADOOP_HOME/logs
ENV HADOOP_TEMP=${HADOOP_HOME}/tmp
ENV HADOOP_DFS=$HADOOP_HOME/data/dfs
ENV HADOOP_NAMENODE=${HADOOP_DFS}/namenode
ENV HADOOP_DATANODE=${HADOOP_DFS}/datanode
ENV PATH=$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$PATH

# 设置用户权限
RUN useradd -m -d "/home/hadoop" -s "/bin/bash" \
        --comment "pseudo-user" "hadoop" && \
    passwd -l hadoop && \
    chown -R hadoop:hadoop ${HADOOP_HOME} && \
    chown -R hadoop:hadoop /home/hadoop

# 运行配置脚本
USER hadoop
WORKDIR /home/hadoop
COPY config-hadoop.sh .
RUN bash config-hadoop.sh

# 检查 Hadoop 是否成功安装
RUN hadoop version || exit 1

#====================================================

# 下载、安装 ZooKeeper

ENV ZOOKEEPER_TGZ="apache-zookeeper-${ZOOKEEPER_VERSION}-bin.tar.gz"
ENV ZOOKEEPER_URL=https://mirrors.aliyun.com/apache/zookeeper/zookeeper-${ZOOKEEPER_VERSION}/${ZOOKEEPER_TGZ}
USER root
WORKDIR /tmp
RUN set -eux; \
    curl -f -L -O "${ZOOKEEPER_URL}"; \
    tar -xzf ${ZOOKEEPER_TGZ} -C /opt; \
    mv /opt/apache-zookeeper-${ZOOKEEPER_VERSION}-bin /opt/zookeeper; \
    rm -f ${ZOOKEEPER_TGZ}*;

ENV ZOOKEEPER_HOME=/opt/zookeeper
ENV PATH=$ZOOKEEPER_HOME/bin:$PATH

# 设置 ZooKeeper 用户权限
RUN useradd -m -d "/home/zookeeper" -s "/bin/bash" \
        --comment "pseudo-user" "zookeeper" && \
    passwd -l zookeeper && \
    chown -R zookeeper:zookeeper ${ZOOKEEPER_HOME} && \
    chown -R zookeeper:zookeeper /home/zookeeper

# 运行 ZooKeeper 配置脚本
USER zookeeper
WORKDIR /home/zookeeper
COPY config-zookeeper.sh .
ENV ZOO_DAT_DIR=/home/zookeeper/data
ENV ZOO_LOG_DIR=/home/zookeeper/logs
RUN bash config-zookeeper.sh

#====================================================

# 下载、安装 Spark

ENV SPARK_TGZ=spark-${SPARK_VERSION}-bin-hadoop${SPARK_HADOOP_VERSION}.tgz
ENV SPARK_URL=https://mirrors.aliyun.com/apache/spark/spark-${SPARK_VERSION}/${SPARK_TGZ}
USER root
WORKDIR /tmp
RUN set -eux; \
    curl -f -L -O "${SPARK_URL}"; \
    # 解压到/opt目录
    tar -xzf ${SPARK_TGZ} -C /opt; \
    mv /opt/spark-${SPARK_VERSION}-bin-hadoop${SPARK_HADOOP_VERSION} /opt/spark; \
    # 清理下载的临时文件
    rm -f $SPARK_TGZ;

# 配置 Spark

ENV SPARK_HOME=/opt/spark
ENV SPARK_CONF=$SPARK_HOME/conf
ENV PATH=$SPARK_HOME/bin:$PATH

# 设置用户权限
RUN useradd -m -d "/home/spark" -s "/bin/bash" \
        --comment "pseudo-user" "spark" && \
    passwd -l spark && \
    chown -R spark:spark ${SPARK_HOME} && \
    chown -R spark:spark /home/spark

# 运行配置脚本
USER spark
WORKDIR /home/spark
COPY config-spark.sh .
RUN bash config-spark.sh

RUN spark-submit --version || exit 1

#====================================================

# 下载、安装 HBase

ENV HBASE_TGZ=hbase-${HBASE_VERSION}-bin.tar.gz
ENV HBASE_URL=https://mirrors.aliyun.com/apache/hbase/${HBASE_VERSION}/${HBASE_TGZ}
USER root
WORKDIR /tmp
RUN set -eux; \
    curl -f -L -O "${HBASE_URL}"; \
    # 解压到/opt目录
    tar -xzf ${HBASE_TGZ} -C /opt; \
    mv /opt/hbase-${HBASE_VERSION} /opt/hbase; \
    # 清理临时文件
    rm -f ${HBASE_TGZ}*;

# 配置 HBase

ENV HBASE_HOME=/opt/hbase
ENV HBASE_CONF=$HBASE_HOME/conf
ENV PATH=$HBASE_HOME/bin:$PATH
# 禁止 HBase 管理 ZK
ENV HBASE_MANAGES_ZK=false

# 设置用户权限
RUN useradd -m -d "/home/hbase" -s "/bin/bash" \
        --comment "pseudo-user" "hbase" && \
    passwd -l hbase && \
    chown -R hbase:hbase ${HBASE_HOME} && \
    chown -R hbase:hbase /home/hbase

# 运行配置脚本
USER hbase
WORKDIR /home/hbase
COPY config-hbase.sh .
RUN bash config-hbase.sh

# 检查 HBase 是否成功安装
RUN hbase version || exit 1

#====================================================

# 暴露端口
EXPOSE 16000 16010 16020 16030 8080 9090 2181 8085 9095 16201 16301

USER root
WORKDIR /app
COPY hbase_schema.sh .
COPY --chown=hbase:hbase preprocess.py .
COPY --chown=spark:spark hbase_reader.py .
COPY --chown=spark:spark spark_query.py .
COPY --chown=spark:spark spark_aggregate.py .
COPY --chown=spark:spark spark_analysis.py .
COPY --chown=spark:spark spark_run.sh .
RUN mkdir -p /app/spark_result && \
    chown -R spark:spark /app/spark_result && \
    mkdir -p /app/preprocess && \
    chown -R hbase:hbase /app/preprocess
RUN echo "export PATH=$PATH" >> /home/hadoop/.profile && \
    echo "export PATH=$PATH" >> /home/zookeeper/.profile && \
    echo "export PATH=$PATH" >> /home/spark/.profile && \
    echo "export PATH=$PATH" >> /home/hbase/.profile

COPY entrypoint.sh .
