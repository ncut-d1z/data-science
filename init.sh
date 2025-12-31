# 把 Windows 换行符改为 Linux 换行符
find . -type f \( -name "*.sh" -o -name "*.md" -o -name "*.sql" -o -name "*.xml" -o -name "*.java" \) -exec sed -i 's/\r$//' {} +
