#!/bin/bash

if [[ $(whoami) != "root" ]]; then
    echo "错误：此脚本必须以root权限运行!"
    exit 1
fi

set -eux

# 确保 Windows 换行符改为 Linux 换行符
find . -type f \( -name "*.sh" -o -name "*.md" -o -name "*.sql" -o -name "*.xml" -o -name "*.java" \) -exec sed -i 's/\r$//' {} +

# 修改权限
chmod -R root:root dev
find . -type f -exec chmod 0644 {} +
find . -type d -exec chmod 0755 {} +

# 开始执行
cd dev
bash setup.sh
bash start-all.sh
bash run-task.sh
