#!/bin/bash

# 确保 /app/preprocess 目录存在，用于存放生成的图片
mkdir -p /app/preprocess

# 提交 Spark 任务

# 在本地启动一个Spark进程，加载HBase连接器，
# 然后运行spark_query.py脚本，
# 让该脚本能够通过ZooKeeper连接到名为hbase的HBase服务并进行数据操作。
#
# spark-submit 是Spark 提供的官方脚本命令，用于向集群（或本地）提交并运行打包好的 Spark 应用程序（通常是 .py 或 .jar 文件）
# `--master local[*]` 用来设置 Spark 的运行模式。`local[*]` 表示在本地机器上以单机模式运行，其中 * 表示使用所有可用的 CPU 逻辑核心进行计算。
# `--packages org.apache.hbase.connectors.spark:hbase-spark:1.0.0` 用来动态指定依赖库。Spark 会自动从 Maven 中央仓库下载 hbase-spark 这个官方连接器库（版本 2.5.0），它提供了 Spark 读写 HBase 所需的 API。这省去了手动将jar包放入classpath的麻烦。
# `--repositories https://maven.aliyun.com/repository/public` 用来临时指定阿里云镜像源
#           持久化方案则是配置 $SPARK_HOME/conf/ivysettings.xml，并添加以下内容
#                   ```xml
#                   <ivysettings>
#                     <settings defaultResolver="aliyun"/>
#                     <resolvers>
#                       <ibiblio name="aliyun" m2compatible="true" root="https://maven.aliyun.com/repository/public"/>
#                     </resolvers>
#                   </ivysettings>
#                   ```
#           再在执行 spark-submit 时加上 `--conf spark.ivy.settings.file=/opt/spark/conf/ivysettings.xml` 参数
# `--conf spark.hadoop.hbase.zookeeper.quorum=hbase` 用来配置 HBase 的连接地址。这是最关键的一项配置，它告诉 Spark：HBase 所使用的 ZooKeeper 集群地址是 hbase。在 Docker Compose 环境中，hbase 正是 HBase 服务的容器名称。因此，Spark 容器可以通过这个主机名直接访问到 HBase 容器。
# `--conf spark.hadoop.hbase.zookeeper.property.clientPort=2181` 用来配置 ZooKeeper 的连接端口。指定 ZooKeeper 的服务端口为默认的 2181。这个端口需要与 HBase 容器中 ZooKeeper 的实际暴露端口一致。


# 候选包列表（按优先级）
PACKAGES=(
    "org.apache.hbase.connectors.spark:hbase-spark:1.0.0"
    "org.apache.hbase:hbase-spark:2.5.0"
)

# 候选仓库列表（按优先级）
REPOSITORIES=(
    "https://maven.aliyun.com/repository/public"
    "https://repo1.maven.org/maven2"
)

check_package_in_repo() {
    local pkg="$1"
    local repo="$2"

    # 解析 groupId:artifactId:version
    IFS=':' read -r group_id artifact_id version <<< "$pkg"

    # 转换 groupId 为路径
    group_path=$(echo "$group_id" | tr '.' '/')
    pom_url="$repo/$group_path/$artifact_id/$version/$artifact_id-$version.pom"

    echo "Checking $pom_url"
    if curl -sf --connect-timeout 10 --max-time 30 "$pom_url" > /dev/null; then
        return 0
    else
        return 1
    fi
}

# 尝试所有 (package, repository) 组合
SELECTED_PACKAGE=""
SELECTED_REPO=""

# 使用预检查代替 spark-submit 测试
#       ✅ 优点：更快、更轻量
#       ❌ 缺点：只能检查 POM 文件是否存在，不能验证 Spark 是否能正确加载
for pkg in "${PACKAGES[@]}"; do
    for repo in "${REPOSITORIES[@]}"; do
        if check_package_in_repo "$pkg" "$repo"; then
            SELECTED_PACKAGE="$pkg"
            SELECTED_REPO="$repo"
            break 2
        fi
    done
done

# 利用 curl 仍然没有找到所需连接器，于是用 spark 本身再尝试一次
if [ -z "$SELECTED_PACKAGE" ]; then

    for pkg in "${PACKAGES[@]}"; do
        for repo in "${REPOSITORIES[@]}"; do
            echo "Trying package '$pkg' from repository '$repo'..."

            if timeout 60 spark-submit \
                --master local[1] \
                --packages "$pkg" \
                --repositories "$repo" \
                --conf spark.ui.enabled=false \
                --conf spark.sql.ui.enabled=false \
                --conf spark.driver.extraJavaOptions=-Dlog4j2.level=WARN \
                /opt/spark/examples/src/main/python/pi.py 1 > /dev/null 2>&1; then

                echo "✅ Success! Using package: $pkg"
                echo "✅ Using repository: $repo"
                SELECTED_PACKAGE="$pkg"
                SELECTED_REPO="$repo"
                break 2  # 跳出两层循环
            else
                echo "❌ Failed to resolve '$pkg' from '$repo'"
            fi
        done
    done

fi

if [ -z "$SELECTED_PACKAGE" ]; then
    echo "❌ All HBase Spark connector candidates failed. Exiting."
    exit 1
fi

# 构建最终的 spark-submit 参数
COMMON_ARGS=(
    --master local[*]
    --packages "$SELECTED_PACKAGE,org.postgresql:postgresql:42.6.0"
    --repositories "$SELECTED_REPO"
    --conf spark.hadoop.hbase.zookeeper.quorum=hbase
    --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181
)

# 提交各个任务
spark-submit "${COMMON_ARGS[@]}" spark_preprocess.py
spark-submit "${COMMON_ARGS[@]}" spark_query.py
spark-submit "${COMMON_ARGS[@]}" spark_aggregate.py
spark-submit "${COMMON_ARGS[@]}" spark_analysis.py
