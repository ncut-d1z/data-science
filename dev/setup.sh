#!/bin/bash

set -eux

source env.sh

sed -i 's@http://.*archive.ubuntu.com@http://mirrors.aliyun.com@g' /etc/apt/sources.list
sed -i 's@http://security.ubuntu.com@http://mirrors.aliyun.com@g' /etc/apt/sources.list
apt-get clean
apt-get update
apt-get upgrade -y
# 确保安装 procps 以使用 ps 命令
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
    net-tools \
    procps

mkdir -p /var/run/sshd
sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/#PubkeyAuthentication yes/PubkeyAuthentication yes/' /etc/ssh/sshd_config

java -version
python3 -V && python3 -m pip --version

mkdir -p /root/.pip
echo '[global]' > /root/.pip/pip.conf
echo 'index-url = https://mirrors.aliyun.com/pypi/simple/' >> /root/.pip/pip.conf
echo 'trusted-host = mirrors.aliyun.com' >> /root/.pip/pip.conf
python3 -m pip install --upgrade pip || \
    apt-get install -y python3-venv || true
apt-get install -y \
        python3-numpy \
        python3-pandas \
        python3-matplotlib \
        python3-psycopg2 || \
    python3 -m pip install --no-cache-dir \
        numpy \
        pandas \
        matplotlib \
        psycopg2-binary || \
    echo "Use 'python3 -m venv .venv && bash .venv/bin/activate' to install other pip packages!"

# python3 -m pip install --no-cache-dir happybase

if [ -f "${HADOOP_TGZ}" ]; then
    echo "Found cached ${HADOOP_TGZ}, skipping download."
else
    curl -f -L -O "${HADOOP_URL}"
fi
rm -rf "/opt/hadoop-${HADOOP_VERSION}" /opt/hadoop
tar -xzf ${HADOOP_TGZ} -C /opt
mv "/opt/hadoop-${HADOOP_VERSION}" /opt/hadoop

# 彻底清理并重置 Hadoop 用户
# 查询并强制杀死残留进程
# ps -u 指定用户, -o pid= 仅输出PID不带表头, 2>/dev/null 屏蔽用户不存在的错误
HADOOP_PIDS=$(ps -u hadoop -o pid= 2>/dev/null || true)
if [ -n "$HADOOP_PIDS" ]; then
    echo "Killing running hadoop processes: $HADOOP_PIDS"
    # 这里的 kill -9 不带引号以允许参数展开，|| true 防止报错
    kill -9 $HADOOP_PIDS || true
fi
# 强制删除用户
userdel -f hadoop || true
# 强制删除家目录
rm -rf /home/hadoop/
# 重建用户
useradd -m -d "/home/hadoop" -s "/bin/bash" \
    --comment "pseudo-user" "hadoop"
passwd -l hadoop
chown -R hadoop:hadoop ${HADOOP_HOME}
chown -R hadoop:hadoop /home/hadoop

bash config-hadoop.sh

hadoop version

if [ -f "${ZOOKEEPER_TGZ}" ]; then
    echo "Found cached ${ZOOKEEPER_TGZ}, skipping download."
else
    curl -f -L -O "${ZOOKEEPER_URL}"
fi
rm -rf "/opt/apache-zookeeper-${ZOOKEEPER_VERSION}-bin"
tar -xzf ${ZOOKEEPER_TGZ} -C /opt
mv "/opt/apache-zookeeper-${ZOOKEEPER_VERSION}-bin" /opt/zookeeper

# 彻底清理并重置 Zookeeper 用户
ZK_PIDS=$(ps -u zookeeper -o pid= 2>/dev/null || true)
if [ -n "$ZK_PIDS" ]; then
    echo "Killing running zookeeper processes: $ZK_PIDS"
    kill -9 $ZK_PIDS || true
fi
userdel -f zookeeper || true
rm -rf /home/zookeeper/
useradd -m -d "/home/zookeeper" -s "/bin/bash" \
    --comment "pseudo-user" "zookeeper"
passwd -l zookeeper
chown -R zookeeper:zookeeper ${ZOOKEEPER_HOME}
chown -R zookeeper:zookeeper /home/zookeeper

bash config-zookeeper.sh

if [ -f "${HBASE_TGZ}" ]; then
    echo "Found cached ${HBASE_TGZ}, skipping download."
else
    curl -f -L -O "${HBASE_URL}"
fi
rm -rf "/opt/hbase-${HBASE_VERSION}"
tar -xzf ${HBASE_TGZ} -C /opt
mv "/opt/hbase-${HBASE_VERSION}" /opt/hbase

# 彻底清理并重置 hbase 用户
HBASE_PIDS=$(ps -u hbase -o pid= 2>/dev/null || true)
if [ -n "$HBASE_PIDS" ]; then
    echo "Killing running hbase processes: $HBASE_PIDS"
    kill -9 $HBASE_PIDS || true
fi
userdel -f hbase || true
rm -rf /home/hbase/
# 新建 hbase 用户
useradd -m -d "/home/hbase" -s "/bin/bash" \
    --comment "pseudo-user" "hbase"
passwd -l hbase
chown -R hbase:hbase ${HBASE_HOME}
chown -R hbase:hbase /home/hbase

bash config-hbase.sh

hbase version

echo "export PATH=$PATH" >> /home/hadoop/.profile
echo "export PATH=$PATH" >> /home/zookeeper/.profile
echo "export PATH=$PATH" >> /home/hbase/.profile
