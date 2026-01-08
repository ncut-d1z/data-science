SELECT
    COUNT(*) as count_line,
    COUNT(DISTINCT road_seg_id) as count_site,
    MIN(data_time) as min_time,
    MAX(data_time) as max_time,
    AVG(volume) as avg_volume,
    AVG(speed) as avg_speed
FROM raw_traffic_data;

SELECT
    SUM(CASE WHEN volume IS NULL THEN 1 ELSE 0 END) as volume_null,
    SUM(CASE WHEN speed IS NULL THEN 1 ELSE 0 END) as speed_null,
    SUM(CASE WHEN road_seg_id IS NULL THEN 1 ELSE 0 END) as id_null
FROM raw_traffic_data;

WITH stats AS (
    SELECT
        COUNT(*) AS total_rows, MIN(volume) AS v_min, MAX(volume) AS v_max, MIN(speed) AS s_min, MAX(speed) AS s_max,
        percentile_cont(0.25) WITHIN GROUP (ORDER BY volume) AS v_q1,
        percentile_cont(0.50) WITHIN GROUP (ORDER BY volume) AS v_q2,
        percentile_cont(0.75) WITHIN GROUP (ORDER BY volume) AS v_q3,
        percentile_cont(0.25) WITHIN GROUP (ORDER BY speed) AS s_q1,
        percentile_cont(0.50) WITHIN GROUP (ORDER BY speed) AS s_q2,
        percentile_cont(0.75) WITHIN GROUP (ORDER BY speed) AS s_q3
    FROM raw_traffic_data
),
counts AS (
    SELECT
        COUNT(*) FILTER (WHERE volume < s.v_q1 OR volume > s.v_q3) AS v_outlier_cnt,
        COUNT(*) FILTER (WHERE speed < s.s_q1 OR speed > s.s_q3) AS s_outlier_cnt
    FROM raw_traffic_data t, stats s
)
SELECT
    'Volume' AS field_name, s.v_min AS min, s.v_q1 AS Q1, s.v_q2 AS Q2, s.v_q3 AS Q3, s.v_max AS max,
    c.v_outlier_cnt AS abnormal_count, s.total_rows AS total_count,
    ROUND((c.v_outlier_cnt::numeric / NULLIF(s.total_rows, 0)) * 100, 2) || '%' AS abnormal_ratio
FROM stats s, counts c
UNION ALL
SELECT
    'Speed' AS field_name, s.s_min AS min, s.s_q1 AS Q1, s.s_q2 AS Q2, s.s_q3 AS Q3, s.s_max AS max,
    c.s_outlier_cnt AS abnormal_count, s.total_rows AS total_count,
    ROUND((c.s_outlier_cnt::numeric / NULLIF(s.total_rows, 0)) * 100, 2) || '%' AS abnormal_ratio
FROM stats s, counts c;
