package com.traffic.job;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MyHadoopAggregate {

    static class AggregatedData {
        String roadSegId;
        String window;
        double sumVolume = 0;
        double sumSpeed = 0;
        int count = 0;

        AggregatedData(String roadSegId, String window) {
            this.roadSegId = roadSegId;
            this.window = window;
        }

        void add(double volume, double speed) {
            sumVolume += volume;
            sumSpeed += speed;
            count++;
        }

        double avgSpeed() {
            return count == 0 ? 0 : sumSpeed / count;
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("traffic_data"));
        Table aggTable = conn.getTable(TableName.valueOf("traffic_agg_15min"));

        File out = new File("./result/agg.csv");
        out.getParentFile().mkdirs();
        PrintWriter csvWriter = new PrintWriter(new FileWriter(out));
        csvWriter.println("road_seg_id,time_window,sum_volume,avg_speed,count");

        Scan scan = new Scan();
        scan.setCaching(500);
        scan.setBatch(100);

        ResultScanner scanner = table.getScanner(scan);

        Map<String, AggregatedData> windowAgg = new HashMap<>();
        String currentWindow = null;

        for (Result r : scanner) {
            String rowKey = Bytes.toString(r.getRow());
            String[] parts = rowKey.split("\\|");
            if (parts.length < 3) continue;

            String timeStr = parts[0];
            String roadId = parts[2];
            String window = to15MinWindow(timeStr);

            if (currentWindow != null && !window.equals(currentWindow)) {
                flushWindow(windowAgg, aggTable, csvWriter);
                windowAgg.clear();
            }
            currentWindow = window;

            byte[] volBytes = r.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume"));
            byte[] spdBytes = r.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed"));
            if (volBytes == null || spdBytes == null) continue;

            double volume = Double.parseDouble(Bytes.toString(volBytes));
            double speed = Double.parseDouble(Bytes.toString(spdBytes));

            windowAgg
                .computeIfAbsent(roadId, k -> new AggregatedData(roadId, window))
                .add(volume, speed);
        }

        flushWindow(windowAgg, aggTable, csvWriter);

        scanner.close();
        csvWriter.close();
        table.close();
        aggTable.close();
        conn.close();
    }

    private static void flushWindow(
            Map<String, AggregatedData> data,
            Table aggTable,
            PrintWriter csvWriter) throws IOException {

        List<Put> puts = new ArrayList<>();

        for (AggregatedData d : data.values()) {
            String rowKey = d.roadSegId + "|" + d.window;
            Put put = new Put(Bytes.toBytes(rowKey));
            put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("sum_volume"),
                    Bytes.toBytes(String.valueOf(d.sumVolume)));
            put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("avg_speed"),
                    Bytes.toBytes(String.valueOf(d.avgSpeed())));
            put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("count"),
                    Bytes.toBytes(String.valueOf(d.count)));
            puts.add(put);

            csvWriter.println(
                    d.roadSegId + "," + d.window + "," +
                    d.sumVolume + "," + d.avgSpeed() + "," + d.count
            );
        }

        if (!puts.isEmpty()) {
            aggTable.put(puts);
        }
        csvWriter.flush();
    }

    private static String to15MinWindow(String timeStr) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            Calendar c = Calendar.getInstance();
            c.setTime(in.parse(timeStr));
            int m = c.get(Calendar.MINUTE);
            c.set(Calendar.MINUTE, (m / 15) * 15);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(c.getTime());
        } catch (Exception e) {
            return timeStr;
        }
    }
}
