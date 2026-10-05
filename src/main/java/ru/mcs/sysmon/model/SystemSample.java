package ru.mcs.sysmon.model;

import java.util.Locale;

/** Machine-wide metrics for the USE method: utilization + saturation per resource. */
public record SystemSample(long tsMs, double cpuPct, int logicalCpus,
                           long memTotalMb, long memAvailMb, long commitUsedMb, long commitLimitMb,
                           double pagesInPs, double pagesOutPs,
                           double diskBusyPct, double diskQueue, double diskReadKbps, double diskWriteKbps) {

    public static final String HEADER = "ts_ms,cpu_pct,logical_cpus,mem_total_mb,mem_avail_mb,"
            + "commit_used_mb,commit_limit_mb,pages_in_ps,pages_out_ps,"
            + "disk_busy_pct,disk_queue,disk_read_kbps,disk_write_kbps";

    public String toCsv() {
        return String.format(Locale.ROOT, "%d,%.2f,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.2f,%.1f,%.1f",
                tsMs, cpuPct, logicalCpus, memTotalMb, memAvailMb, commitUsedMb, commitLimitMb,
                pagesInPs, pagesOutPs, diskBusyPct, diskQueue, diskReadKbps, diskWriteKbps);
    }
}
