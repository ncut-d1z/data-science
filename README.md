# 交通监测数据处理与分析系统

## 一、项目简介
本项目基于 PostgreSQL、HBase、PySpark 与 Docker，完成从原始交通监测 CSV 数据导入、清洗、扩增，到分布式存储与分析的完整流程，适用于大数据课程实验与工程实践。

---
## 二、项目目录结构与脚本功能说明

- **db_init.sql**
  PostgreSQL 初始化脚本，用于创建数据库与原始数据表，不设置任何约束，保证原始数据完整导入。

- **preprocess.py**
  数据预处理脚本，完成以下功能：
  1. 从 PostgreSQL 读取原始数据；
  2. 数据概况与缺失值统计；
  3. 基于四分位数的异常值检测与箱线图绘制；
  4. 缺失值处理（丢弃与前向填充）；
  5. 异常值截断；
  6. 数据扩增（2 天扩展至不少于 7 天，加入 ±5% 随机扰动）；
  7. 将清洗与扩增后的数据写入 HBase，采用连接池与批量提交机制提升写入性能。

- **hbase_schema.sh**
  HBase 建表脚本，用于创建 traffic 表及列族 d。

- **spark_query.py**
  PySpark 简单查询示例，实现对指定监测点、指定日期与小时的分钟级数据查询。

- **spark_aggregate.py**
  PySpark 聚合分析脚本，将分钟级数据聚合为 15 分钟粒度，流量求和、速度取均值。

- **spark_analysis.py**
  PySpark 数据分析脚本，计算监测点间的皮尔逊相关系数，并按相关性大小排序输出。

- **docker-compose.yml**
  定义 PostgreSQL、HBase、Python 运行环境，实现一键部署。

- **docker_run.sh**
  宿主机执行的一键启动脚本，完成容器启动、数据库初始化、数据处理与写入的全流程自动化。

---
## 三、系统设计说明

系统采用“关系型数据库 + 分布式列存储 + 分布式计算引擎”的经典大数据处理架构。PostgreSQL 用于承载原始数据，保证数据导入的灵活性与完整性；Python 与 Pandas 负责数据清洗与质量提升；HBase 用于存储大规模时序数据，支持高并发随机读写；PySpark 则用于分布式查询、聚合与统计分析，满足后续分析扩展需求。

---
## 四、HBase 行键设计理由

HBase 行键采用以下格式：

```
salt_roadSegId_dataTime
```

设计理由如下：
1. **盐化前缀（salt）**：通过对监测点 ID 取哈希并取模，打散行键分布，避免时间序列数据集中写入导致的 RegionServer 热点问题；
2. **roadSegId**：保证同一监测点的数据在逻辑上可聚集，便于按监测点查询；
3. **dataTime**：保留时间顺序信息，支持时间范围扫描。

该行键设计在负载均衡与查询效率之间取得良好折中。

---
## 五、Spark–HBase Connector 提交示例

```bash
spark-submit \
  --master local[*] \
  --packages org.apache.hbase:hbase-spark:2.4.17 \
  --conf spark.hadoop.hbase.zookeeper.quorum=localhost \
  --conf spark.hadoop.hbase.zookeeper.property.clientPort=2181 \
  spark_query.py
```

---
## 六、运行步骤

```bash
chmod +x docker_run.sh
./docker_run.sh
```

执行完成后，即可使用 PySpark 脚本对 HBase 中的数据进行查询与分析。
