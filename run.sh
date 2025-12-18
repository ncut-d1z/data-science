#!/bin/bash
# 一键启动与初始化脚本（在宿主机执行）

set -eux

# 数据文件拼接
if [ ! -d "data" ]; then
  echo "数据文件缺失! 请将 *.csv 文件放到当前目录的子目录 ./data 中!"
fi
if [ ! -f "data/final.csv" ]; then
  echo "拼接数据文件 final.csv ..."
  mv data data-old
  awk 'NR==1 || FNR>1' data-old/*.csv > data/final.csv
else
  echo "数据文件 final.csv 已存在，跳过拼接"
fi

echo "=== 检查 Docker Compose 环境 ==="

# 函数：检查命令是否存在
check_command() {
    command -v "$1" > /dev/null 2>&1
}

if ! check_command "docker"; then
    echo "Docker 未安装，正在尝试自动安装..."
    # 安装必要工具并添加官方仓库
    dnf install -y yum-utils
    yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    # 安装 Docker 引擎
    dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
    systemctl start docker
    systemctl enable docker
fi


# 备份配置文件
cp /etc/docker/daemon.json /etc/docker/daemon.json.bak 2>/dev/null || true
# 配置 Docker 国内镜像加速器
tee /etc/docker/daemon.json <<-'EOF'
{
    "default-address-pools": [
        {
            "base": "10.255.0.0/16",
            "size": 24
        }
    ],
    "registry-mirrors": [
        "https://mirrors-ssl.aliyuncs.com/",
        "https://docker.mirrors.ustc.edu.cn",
        "https://hub-mirror.c.163.com",
        "https://mirror.baidubce.com"
    ]
}
EOF

systemctl daemon-reload
systemctl restart docker
docker info | grep -A 1 "Registry Mirrors"


# 智能选择可用的 Compose 命令
COMPOSE_CMD="not_found"
if check_command "docker"; then
    # 优先检查插件版 'docker compose' (官方推荐，性能更优)
    if docker compose version > /dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
        echo "✅ 检测到 Docker Compose 插件版 (docker compose)"
    # 其次检查独立版 'docker-compose'
    elif check_command "docker-compose"; then
        COMPOSE_CMD="docker-compose"
        echo "✅ 检测到 Docker Compose 独立版 (docker-compose)"
    fi
else
    echo "❌ 未检测到 Docker 引擎，请先安装 Docker。"
    exit 1
fi

# 如果均未找到，则进行安装
if [ "$COMPOSE_CMD" = "not_found" ]; then
    echo "未检测到 Docker Compose，正在安装 docker-compose-plugin..."
    # 根据系统使用正确的包管理器 (Alibaba Linux / RHEL/CentOS 使用 dnf)
    if sudo dnf install -y docker-compose-plugin; then
        echo "✅ docker-compose-plugin 安装成功"
        COMPOSE_CMD="docker compose"
    else
        echo "❌ Docker Compose 安装失败，请检查网络或权限。"
        exit 1
    fi
fi

echo "使用的 Compose 命令: $COMPOSE_CMD"
echo "================================"
echo ""

echo "[2/6] 启动 Docker Compose 服务"
$COMPOSE_CMD up -d --build

mkdir -p ./spark_result

echo "✅ 全流程执行完成"
