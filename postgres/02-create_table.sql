-- 切换数据库
\connect traffic_db;

-- 创建原始数据表（不加任何约束）
CREATE TABLE raw_traffic_data (
    road_seg_id VARCHAR(100),
    data_time   VARCHAR(20),
    volume      INTEGER,
    speed       FLOAT
);

-- 导入 CSV 数据
-- awk 'NR==1 || FNR>1' data-old/*.csv > data/final.csv
-- grep -v "road_seg_id" final.csv | su - postgres -c "psql -U postgres -d traffic_db -c '\copy raw_traffic_data(road_seg_id, data_time, volume, speed) FROM STDIN WITH (FORMAT csv, HEADER false)'"
