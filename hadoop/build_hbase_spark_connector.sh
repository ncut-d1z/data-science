#!/bin/bash

# ==============================================================================
# 脚本名称: build_hbase_spark_connector.sh
# 功能描述: 自动检测依赖，使用阿里云镜像加速，动态定位 Spark 模块并编译
# 适配系统: Ubuntu 24.04 (支持自动 apt-get)
# ==============================================================================

# --- 配置项 ---
REPO_URL="https://github.com/apache/hbase-connectors.git"
WORK_DIR="/tmp/hbase_connector_build_ws"
TARGET_DIR="/opt/spark/jars"  # 默认部署路径，如果没有写权限会自动回退到当前目录
CUSTOM_HBASE_VERSION="2.4.17" # 如果无法自动检测，使用此默认值
# 临时 Maven 设置文件路径
MAVEN_SETTINGS="/tmp/hbase_build_settings.xml"

# --- 颜色输出工具 ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO] $(date '+%H:%M:%S') > $1${NC}"; }
log_succ() { echo -e "${GREEN}[SUCCESS] $(date '+%H:%M:%S') > $1${NC}"; }
log_warn() { echo -e "${YELLOW}[WARN] $(date '+%H:%M:%S') > $1${NC}"; }
log_err() { echo -e "${RED}[ERROR] $(date '+%H:%M:%S') > $1${NC}"; }

# 标记是否执行过 apt-get update
APT_UPDATED=false

# ==============================================================================
# 0. 网络检查与配置生成 (关键修复)
# ==============================================================================
check_network_and_config() {
    log_info "Checking network connectivity..."
    # 尝试 ping 阿里云的一个公共 DNS，确认网是通的
    if ! ping -c 1 223.5.5.5 &> /dev/null; then
        log_warn "Network might be unreachable (ping 223.5.5.5 failed)."
        log_warn "Please check your VirtualBox Network Adapter (NAT/Bridged)."
    else
        log_succ "Network connectivity confirmed."
    fi

    log_info "Generating Aliyun Maven Settings for speed..."
    cat > "$MAVEN_SETTINGS" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <mirrorOf>*</mirrorOf>
      <name>阿里云公共仓库</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF
    log_succ "Maven settings created at $MAVEN_SETTINGS"
}

# ==============================================================================
# 1. 依赖检查 (Ubuntu 24.04)
# ==============================================================================
ensure_apt_update() {
    if [ "$APT_UPDATED" = "false" ]; then
        log_info "Updating apt package list..."
        sudo apt-get update -y > /dev/null
        APT_UPDATED=true
    fi
}

check_and_install() {
    local cmd_name="$1"     # 命令名称，如 mvn
    local pkg_name="$2"     # 包名称，如 maven
    local display_name="$3" # 显示名称，如 Maven

    if ! command -v "$cmd_name" &> /dev/null; then
        log_warn "$display_name ($cmd_name) not found."
        log_info "Attempting to install $pkg_name via apt-get..."

        ensure_apt_update

        if sudo apt-get install -y "$pkg_name"; then
            log_succ "$display_name installed successfully."
        else
            log_err "Failed to install $pkg_name. Please install it manually."
            exit 1
        fi
    else
        log_succ "$display_name is already installed."
    fi
}

check_tools() {
    log_info "Checking build tools on Ubuntu 24.04..."

    # 1. 检查 Git
    check_and_install "git" "git" "Git"

    # 2. 检查 Maven
    check_and_install "mvn" "maven" "Maven"

    # 3. 检查 Java (目标: OpenJDK 11)
    check_and_install "java" "openjdk-11-jdk" "Java"
    export JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
    log_succ "Using JAVA_HOME=$JAVA_HOME"
    export PATH=$JAVA_HOME/bin:$PATH
}

# ==============================================================================
# 2. 自动检测环境版本
# ==============================================================================
detect_versions() {
    log_info "Detecting environment versions..."

    # --- 检测 Spark 和 Scala 版本 ---
    if command -v spark-submit &> /dev/null; then
        SPARK_VERSION_RAW=$(spark-submit --version 2>&1)

        # 提取 Spark 版本 (例如 3.4.1)
        DETECTED_SPARK_VER=$(echo "$SPARK_VERSION_RAW" | grep -oP 'version \K[0-9]+\.[0-9]+\.[0-9]+' | head -1)

        # 提取 Scala 版本 (例如 2.12)
        # 很多 Spark 3.x 输出中包含 "Using Scala version 2.12.18"
        DETECTED_SCALA_VER=$(echo "$SPARK_VERSION_RAW" | grep -oP 'Scala version \K[0-9]+\.[0-9]+' | head -1)
    else
        log_warn "spark-submit not found in PATH."
    fi

    # 设置默认值
    SPARK_VER=${DETECTED_SPARK_VER:-"3.2.1"}
    SCALA_BINARY_VER=${DETECTED_SCALA_VER:-"2.12"} # 默认为 2.12 (Spark 3.x 标准)

    # --- 检测 HBase 版本 ---
    # 如果环境变量 HBASE_VERSION 存在则使用，否则尝试 hbase 命令，否则用默认
    if [ -n "$HBASE_VERSION" ]; then
        HBASE_VER="$HBASE_VERSION"
    elif command -v hbase &> /dev/null; then
        HBASE_VER=$(hbase version 2>&1 | head -n 1 | awk '{print $2}')
    else
        HBASE_VER="$CUSTOM_HBASE_VERSION"
    fi

    log_info "----------------------------------------"
    log_info "Build Configuration:"
    log_info "  Spark Version : $SPARK_VER"
    log_info "  Scala Binary  : $SCALA_BINARY_VER"
    log_info "  HBase Version : $HBASE_VER"
    log_info "----------------------------------------"
}

# ==============================================================================
# 3. 下载/更新源码
# ==============================================================================
prepare_source() {
    log_info "Preparing source code at $WORK_DIR..."

    if [ -d "$WORK_DIR" ]; then
        log_warn "Directory exists. Updating repo..."
        cd "$WORK_DIR" || exit
        git reset --hard
        git clean -fd
        git checkout master
        git pull
    else
        mkdir -p "$WORK_DIR"
        git clone "$REPO_URL" "$WORK_DIR"
        cd "$WORK_DIR" || exit
    fi
}

# ==============================================================================
# 4. 探查与动态定位
# ==============================================================================
inspect_repo_structure() {
    echo ""
    log_info "--- [INSPECTION] Scanning Repository Structure ---"

    cd "$WORK_DIR" || exit

    # 1. 打印所有包含 pom.xml 的目录（深度为 3），展示项目结构
    echo -e "${YELLOW}Available Maven Modules (Directory/pom.xml):${NC}"
    find . -maxdepth 4 -name "pom.xml" -not -path '*/.*' | sort | sed 's|/pom.xml||' | sed 's|^\./||'

    echo ""
    log_info "Searching for 'hbase-spark' module..."

    # 2. 动态寻找 hbase-spark 所在的目录
    # -type d : 找目录
    # -name "hbase-spark" : 名字叫 hbase-spark
    FOUND_PATH=$(find . -type d -name "hbase-spark" -not -path '*/.*' | head -n 1)

    if [ -z "$FOUND_PATH" ]; then
        log_err "Could not find any directory named 'hbase-spark' in the repo."
        log_err "The repository structure might have changed drastically."
        exit 1
    fi

    # 去掉开头的 ./ (例如 ./spark/hbase-spark -> spark/hbase-spark)
    TARGET_MODULE_REL_PATH=${FOUND_PATH#./}

    log_succ "Found target module at: ${YELLOW}${TARGET_MODULE_REL_PATH}${NC}"

    # 导出变量供下一步使用
    export TARGET_MODULE_REL_PATH
    echo ""
}

# ==============================================================================
# 4.5 [关键] 自动补丁修复 SLF4J 报错
# ==============================================================================
patch_source_code() {
    log_info "--- [PATCHING] Fixing SLF4J compatibility for Spark 3.x ---"

    # 定位 Logging.scala 文件
    # 路径通常是: spark/hbase-spark/src/main/scala/org/apache/hadoop/hbase/spark/Logging.scala
    LOGGING_FILE=$(find "$WORK_DIR/$TARGET_MODULE_REL_PATH" -name "Logging.scala")

    if [ -f "$LOGGING_FILE" ]; then
        log_info "Patching file: $LOGGING_FILE"

        # 1. 注释掉 org.slf4j.impl.StaticLoggerBinder 的 import
        sed -i 's/^import org.slf4j.impl.StaticLoggerBinder/\/\/ import org.slf4j.impl.StaticLoggerBinder/' "$LOGGING_FILE"

        # 2. 替换 StaticLoggerBinder.getSingleton... 调用
        # 原理：直接给 binderClass 赋值一个假字符串，绕过对 StaticLoggerBinder 的调用
        # 这样 isLog4j12() 会返回 false，这对 Spark 3.x (Log4j2) 是安全的
        sed -i 's/val binderClass = StaticLoggerBinder.getSingleton.getLoggerFactoryClassStr/val binderClass = "unknown"/g' "$LOGGING_FILE"

        log_succ "Patch applied successfully."
    else
        log_warn "Logging.scala not found. Skipping patch (Build might fail if error persists)."
    fi
}

# ==============================================================================
# 5. 编译项目 (加入 -s 参数)
# ==============================================================================
compile_project() {
    log_info "Starting Maven Build (using Aliyun mirror)..."
    log_info "Target module: $TARGET_MODULE_REL_PATH"

    cd "$WORK_DIR" || exit

    # 使用上一步动态探测到的路径
    # 如果探测到的是 spark/hbase-spark，则 -pl spark/hbase-spark
    # 如果探测到的是 hbase-spark (根目录下)，则 -pl hbase-spark

    MODULE_ARG="-pl ${TARGET_MODULE_REL_PATH} -am"

    # 构造 Maven 命令
    # -pl spark/hbase-spark : 只构建 spark 连接器模块
    # -am : 同时构建依赖的模块
    # -DskipTests : 跳过测试，加快速度
    # -Dspark.version : 指定 Spark 版本
    # -Dscala.binary.version : 指定 Scala 主版本
    # -Dhbase.version : 指定 HBase 版本

    # 注意这里加入了 -s "$MAVEN_SETTINGS"
    CMD="mvn clean package -DskipTests -s $MAVEN_SETTINGS \
        ${MODULE_ARG} \
        -Dspark.version=$SPARK_VER \
        -Dscala.binary.version=$SCALA_BINARY_VER \
        -Dhbase.version=$HBASE_VER"

    log_info "Executing Maven command:"
    echo -e "${YELLOW}$CMD${NC}"

    if $CMD; then
        log_succ "Build Success!"
    else
        log_err "Build Failed. Check the module path and dependencies."
        exit 1
    fi
}

# ==============================================================================
# 6. 部署与验证
# ==============================================================================
deploy_artifact() {
    log_info "Locating built artifact..."

    # 查找构建出的 jar 包 (排除 source 和 javadoc)
    JAR_PATH=$(find "$WORK_DIR/spark/hbase-spark/target" -name "hbase-spark-*.jar" | grep -v "sources" | grep -v "javadoc" | head -n 1)

    if [ -z "$JAR_PATH" ]; then
        log_err "No jar file found in target directory."
        exit 1
    fi

    JAR_NAME=$(basename "$JAR_PATH")
    log_succ "Found JAR: $JAR_NAME"

    # 检查目标目录权限
    if [ ! -d "$TARGET_DIR" ]; then
        log_warn "Directory $TARGET_DIR does not exist. Creating it..."
        sudo mkdir -p "$TARGET_DIR"
    fi

    # 确定目标路径
    if [ ! -w "$TARGET_DIR" ]; then
        log_warn "No write permission for $TARGET_DIR. Trying with sudo..."
        sudo cp "$JAR_PATH" "$TARGET_DIR/$JAR_NAME"
    else
        cp "$JAR_PATH" "$TARGET_DIR/$JAR_NAME" || \
            echo "Fail to deploy $JAR_PATH"
    fi

    FINAL_PATH="$TARGET_DIR/$JAR_NAME"

    log_info "Copying to $FINAL_PATH ..."
    if [ -f "$FINAL_PATH" ]; then
        log_succ "Deployed to $FINAL_PATH"
    else
        log_err "Failed to deploy jar."
        exit 1
    fi

    # 验证 format
    log_info "Verifying package format..."
    CLASS_PATH=$(jar tf "$FINAL_PATH" | grep "DefaultSource.class" | head -n 1)
    if [ -n "$CLASS_PATH" ]; then
        PACKAGE_PATH=${CLASS_PATH%/*}
        FORMAT_STRING=$(echo "$PACKAGE_PATH" | tr '/' '.')

        echo ""
        echo -e "${GREEN}======================================================${NC}"
        echo -e "${GREEN} BUILD & DEPLOY FINISHED ${NC}"
        echo -e "${GREEN}======================================================${NC}"
        echo -e "Jar Location  : ${YELLOW}$FINAL_PATH${NC}"
        echo -e "Usage in Code : ${YELLOW}.format(\"$FORMAT_STRING\")${NC}"
        echo -e "${GREEN}======================================================${NC}"
        echo ""
    else
        log_warn "Could not determine DefaultSource path automatically. Please check the jar manually."
    fi

    # 清理临时配置
    rm -f "$MAVEN_SETTINGS"
}

# ==============================================================================
# 主流程
# ==============================================================================
main() {
    check_network_and_config
    check_tools
    detect_versions
    # prepare_source
    inspect_repo_structure
    patch_source_code
    compile_project
    deploy_artifact
}

main
