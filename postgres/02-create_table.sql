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
COPY raw_traffic_data(road_seg_id, data_time, volume, speed)
FROM PROGRAM 'sh -c "cat /app/data/*.csv"'
WITH ( FORMAT csv, HEADER true, DELIMITER ',', ENCODING 'UTF8' );
