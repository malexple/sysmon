package ru.mcs.sysmon;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SystemMonitor {

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> options = parseArgs(args);
        long intervalSec = Long.parseLong(options.getOrDefault("interval", "10"));
        String outPath = options.getOrDefault("out", "samples/session.csv");

        Path out = Path.of(outPath);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        SystemInfo si = new SystemInfo();
        OperatingSystem os = si.getOperatingSystem();
        Map<Integer, OSProcess> previous = new HashMap<>();

        boolean writeHeader = !Files.exists(out) || Files.size(out) == 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(outPath, true))) {
            if (writeHeader) {
                pw.println("timestamp,pid,name,cpu_percent,resident_mb,disk_read_kb,disk_write_kb");
            }
            System.out.println("Sampling every " + intervalSec + "s into " + outPath + ". Ctrl+C to stop.");

            while (true) {
                List<OSProcess> processes = os.getProcesses();
                String ts = Instant.now().toString();

                for (OSProcess p : processes) {
                    OSProcess prev = previous.get(p.getProcessID());
                    double cpu = prev != null ? p.getProcessCpuLoadBetweenTicks(prev) * 100.0 : 0.0;
                    long residentMb = p.getResidentSetSize() / (1024 * 1024);
                    long readKb = p.getBytesRead() / 1024;
                    long writeKb = p.getBytesWritten() / 1024;

                    // otsekaem shum: processy, kotorye realno nichego ne edyat
                    if (residentMb < 20 && cpu < 1.0) {
                        continue;
                    }

                    pw.printf("%s,%d,%s,%.2f,%d,%d,%d%n",
                            ts, p.getProcessID(), p.getName(), cpu, residentMb, readKb, writeKb);
                }
                pw.flush();

                previous.clear();
                for (OSProcess p : processes) {
                    previous.put(p.getProcessID(), p);
                }

                TimeUnit.SECONDS.sleep(intervalSec);
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                String[] kv = a.substring(2).split("=", 2);
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }
}