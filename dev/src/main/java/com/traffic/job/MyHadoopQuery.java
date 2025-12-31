package com.traffic.job;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.*;
import java.util.*;

public class MyHadoopQuery {

    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("traffic_data"));

        String sampledRoadId = reservoirSampleRoadId(table);
        if (sampledRoadId == null) {
            throw new RuntimeException("未采样到 road_seg_id");
        }

        List<String> results = query(table, sampledRoadId,
                "2024-03-01 08:00:00",
                "2024-03-02 08:00:00");

        File out = new File("./result/query.csv");
        out.getParentFile().mkdirs();
        PrintWriter w = new PrintWriter(new FileWriter(out));
        w.println("time,road_seg_id,volume,speed");
        for (String r : results) {
            w.println(r);
        }
        w.close();

        table.close();
        conn.close();
    }

    private static String reservoirSampleRoadId(Table table) throws IOException {
        Scan scan = new Scan();
        scan.setFilter(new KeyOnlyFilter());
        scan.setCaching(500);

        ResultScanner scanner = table.getScanner(scan);
        Random rand = new Random();
        String selected = null;
        int count = 0;

        for (Result r : scanner) {
            String[] parts = Bytes.toString(r.getRow()).split("\\|");
            if (parts.length < 3) continue;
            String roadId = parts[2];
            count++;
            if (rand.nextInt(count) == 0) {
                selected = roadId;
            }
        }
        scanner.close();
        return selected;
    }

    private static List<String> query(
            Table table,
            String roadId,
            String start,
            String end) throws IOException {

        List<String> res = new ArrayList<>();
        Scan scan = new Scan();
        scan.setCaching(500);

        ResultScanner scanner = table.getScanner(scan);
        for (Result r : scanner) {
            String[] parts = Bytes.toString(r.getRow()).split("\\|");
            if (parts.length < 3) continue;
            if (!parts[2].equals(roadId)) continue;

            String time = parts[0];
            if (time.compareTo(start) < 0 || time.compareTo(end) > 0) continue;

            String vol = Bytes.toString(r.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume")));
            String spd = Bytes.toString(r.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed")));
            res.add(time + "," + roadId + "," + vol + "," + spd);
        }
        scanner.close();
        return res;
    }
}
