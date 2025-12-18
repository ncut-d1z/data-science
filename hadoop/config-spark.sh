#!/bin/bash
# 配置脚本（在容器执行）

set -eux  # 遇到错误立即退出

# 创建必要目录并设置权限
mkdir -p ${SPARK_CONF} \
    && chown -R spark:spark ${SPARK_CONF}

cat > "${SPARK_CONF}/spark-env.sh" << EOF
export JAVA_HOME=$JAVA_HOME
EOF

# 配置 SSH
SPARK_SSH=/home/spark/.ssh
# 为 spark 用户配置 SSH 无密码登录
mkdir -p $SPARK_SSH
chmod 700 $SPARK_SSH
ssh-keygen -t rsa -C 'rsa key for spark' -N '' -P '' -f $SPARK_SSH/id_rsa
cat $SPARK_SSH/id_rsa.pub >> $SPARK_SSH/authorized_keys
chmod 600 $SPARK_SSH/authorized_keys
