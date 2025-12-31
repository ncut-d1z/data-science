package com.traffic.job;

import com.traffic.analysis.MyHadoopCorrAccumulator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.*;
import java.util.*;

public class MyHadoopAnalysis {

    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("traffic_data"));

        Map<String, MyHadoopCorrAccumulator> volumeCorr = new HashMap<>();
        Map<String, MyHadoopCorrAccumulator> speedCorr = new HashMap<>();

        Map<String, Map<String, double[]>> buffer = new HashMap<>();

        Scan scan = new Scan();
        scan.setCaching(500);
        ResultScanner scanner = table.getScanner(scan);

        for (Result r : scanner) {
            String[] parts = Bytes.toString(r.getRow()).split("\\|");
            if (parts.length < 3) continue;

            String time = parts[0];
            String road = parts[2];

            double volume = Double.parseDouble(
                    Bytes.toString(r.getValue(Bytes.toBytes("info"), Bytes.toBytes("volume")))
            );
            double speed = Double.parseDouble(
                    Bytes.toString(r.getValue(Bytes.toBytes("info"), Bytes.toBytes("speed")))
            );

            buffer
                .computeIfAbsent(time, k -> new HashMap<>())
                .put(road, new double[]{volume, speed});

            if (buffer.size() > 1000) {
                consume(buffer, volumeCorr, speedCorr);
                buffer.clear();
            }
        }

        consume(buffer, volumeCorr, speedCorr);

        File out = new File("./result/corr.csv");
        out.getParentFile().mkdirs();
        PrintWriter w = new PrintWriter(new FileWriter(out));
        w.println("site_a,site_b,volume_corr,speed_corr");

        for (String k : volumeCorr.keySet()) {
            MyHadoopCorrAccumulator v = volumeCorr.get(k);
            MyHadoopCorrAccumulator s = speedCorr.get(k);
            w.println(k.replace("|", ",") + "," +
                    v.correlation() + "," + s.correlation());
        }

        w.close();
        scanner.close();
        table.close();
        conn.close();
    }

    private static void consume(
            Map<String, Map<String, double[]>> buffer,
            Map<String, MyHadoopCorrAccumulator> volCorr,
            Map<String, MyHadoopCorrAccumulator> spdCorr) {

        for (Map<String, double[]> snapshot : buffer.values()) {
            List<String> roads = new ArrayList<>(snapshot.keySet());
            for (int i = 0; i < roads.size(); i++) {
                for (int j = i + 1; j < roads.size(); j++) {
                    String a = roads.get(i);
                    String b = roads.get(j);
                    String key = a + "|" + b;

                    double[] da = snapshot.get(a);
                    double[] db = snapshot.get(b);

                    volCorr
                        .computeIfAbsent(key, k -> new MyHadoopCorrAccumulator())
                        .add(da[0], db[0]);

                    spdCorr
                        .computeIfAbsent(key, k -> new MyHadoopCorrAccumulator())
                        .add(da[1], db[1]);
                }
            }
        }
    }
}
