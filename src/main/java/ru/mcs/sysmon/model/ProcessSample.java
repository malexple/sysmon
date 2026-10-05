package ru.mcs.sysmon.model;

import java.util.Locale;

/** One process (or the aggregated "(other)" row) in one sample. cpuPct is 0..100 of the whole machine. */
public record ProcessSample(int pid, String name, int procs, double cpuPct, long rssMb,
                            double readKbps, double writeKbps) {

    public static final String HEADER = "ts_ms,pid,name,procs,cpu_pct,rss_mb,io_read_kbps,io_write_kbps";

    public String toCsv(long tsMs) {
        String safe = name.replaceAll("[,\"\\r\\n]", "_");
        return String.format(Locale.ROOT, "%d,%d,%s,%d,%.2f,%d,%.1f,%.1f",
                tsMs, pid, safe, procs, cpuPct, rssMb, readKbps, writeKbps);
    }
}
