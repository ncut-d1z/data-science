#!/bin/bash

set -eux

source env.sh

sed -i 's@http://.*archive.ubuntu.com@http://mirrors.aliyun.com@g' /etc/apt/sources.list
sed -i 's@http://security.ubuntu.com@http://mirrors.aliyun.com@g' /etc/apt/sources.list
apt-get clean
apt-get update
apt-get upgrade -y
apt-get install -y \
    vim \
    bash \
    curl \
    gnupg \
    openjdk-11-jdk \
    tar \
    gcc \
    libpq-dev \
    python3 python3-pip python3-dev \
    openssh-server openssh-client \
    net-tools

mkdir -p /var/run/sshd
sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/#PubkeyAuthentication yes/PubkeyAuthentication yes/' /etc/ssh/sshd_config

java -version
python3 -V && python3 -m pip --version

mkdir -p /root/.pip
echo '[global]' > /root/.pip/pip.conf
echo 'index-url = https://mirrors.aliyun.com/pypi/simple/' >> /root/.pip/pip.conf
echo 'trusted-host = mirrors.aliyun.com' >> /root/.pip/pip.conf
python3 -m pip install --upgrade pip
python3 -m pip install --no-cache-dir \
    numpy \
    pandas \
    matplotlib \
    psycopg2-binary \
    happybase \
    pyspark

curl -f -L -O "${HADOOP_URL}"
tar -xzf ${HADOOP_TGZ} -C /opt
mv /opt/hadoop-${HADOOP_VERSION} /opt/hadoop

useradd -m -d "/home/hadoop" -s "/bin/bash" \
    --comment "pseudo-user" "hadoop"
passwd -l hadoop
chown -R hadoop:hadoop ${HADOOP_HOME}
chown -R hadoop:hadoop /home/hadoop

bash config-hadoop.sh

hadoop version

curl -f -L -O "${ZOOKEEPER_URL}"
tar -xzf ${ZOOKEEPER_TGZ} -C /opt
mv /opt/apache-zookeeper-${ZOOKEEPER_VERSION}-bin /opt/zookeeper

useradd -m -d "/home/zookeeper" -s "/bin/bash" \
    --comment "pseudo-user" "zookeeper"
passwd -l zookeeper
chown -R zookeeper:zookeeper ${ZOOKEEPER_HOME}
chown -R zookeeper:zookeeper /home/zookeeper

bash config-zookeeper.sh

curl -f -L -O "${SPARK_URL}"
tar -xzf ${SPARK_TGZ} -C /opt
mv /opt/spark-${SPARK_VERSION}-bin-hadoop${SPARK_HADOOP_VERSION} /opt/spark

useradd -m -d "/home/spark" -s "/bin/bash" \
    --comment "pseudo-user" "spark"
passwd -l spark
chown -R spark:spark ${SPARK_HOME}
chown -R spark:spark /home/spark

bash config-spark.sh

spark-submit --version

curl -f -L -O "${HBASE_URL}"
tar -xzf ${HBASE_TGZ} -C /opt
mv /opt/hbase-${HBASE_VERSION} /opt/hbase

useradd -m -d "/home/hbase" -s "/bin/bash" \
    --comment "pseudo-user" "hbase"
passwd -l hbase
chown -R hbase:hbase ${HBASE_HOME}
chown -R hbase:hbase /home/hbase

bash config-hbase.sh

hbase version

mkdir -p /app/spark_result
chown -R spark:spark /app/spark_result
mkdir -p /app/preprocess
chown -R hbase:hbase /app/preprocess

echo "export PATH=$PATH" >> /home/hadoop/.profile
echo "export PATH=$PATH" >> /home/zookeeper/.profile
echo "export PATH=$PATH" >> /home/spark/.profile
echo "export PATH=$PATH" >> /home/hbase/.profile
