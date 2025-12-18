#!/bin/bash
# 一键清理脚本（在宿主机执行）

echo "清理 Docker 历史构建缓存"
docker compose down
docker builder prune -f
docker image prune -f
# 删除所有未被使用的数据卷
docker volume prune -f
# 删除所有未被使用的自定义网络
docker network prune -f
