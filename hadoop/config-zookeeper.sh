#!/bin/bash
# Zookeeper 配置脚本（在容器执行）

set -eux

# 创建数据目录
mkdir -p $ZOO_DAT_DIR
mkdir -p $ZOO_LOG_DIR

# 创建配置文件 zoo.cfg
cat > "${ZOOKEEPER_HOME}/conf/zoo.cfg" << EOF
# 基本心跳时间 (毫秒)
tickTime=2000
# 初始通信时限
initLimit=10
# 同步通信时限
syncLimit=5
# 数据存储目录
dataDir=$ZOO_DAT_DIR
# 客户端连接端口
clientPort=2181
# 最大客户端连接数
maxClientCnxns=60

# Zookeeper 的 AdminServer 与 Spark Master Web UI 都会尝试绑定 8080 端口
# 换绑 8081 端口
admin.serverPort=8081
# 禁用 AdminServer
admin.enableServer=false
EOF

echo "admin.enableServer=false" >> /opt/zookeeper/conf/zoo.cfg

# 配置日志目录（可选，取决于具体 ZK 版本和启动脚本逻辑，通常通过 env 设置）
# 这里确保 zookeeper 用户拥有权限
chown -R zookeeper:zookeeper ${ZOOKEEPER_HOME}
