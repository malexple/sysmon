package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.SystemSample;

/** Saturation criteria. CPU run-queue is not collected, so CPU "saturation" is approximated by high utilization. */
public final class Thresholds {

    public static final double CPU_PCT = 85;
    public static final double MEM_AVAIL_FRACTION = 0.10;
    public static final double PAGES_IN_PS = 500;
    public static final double COMMIT_FRACTION = 0.90;
    public static final double DISK_BUSY_PCT = 80;
    public static final double DISK_QUEUE = 2;
    public static final long MIN_EPISODE_MS = 30_000;
    public static final long MERGE_GAP_MS = 30_000;

    private Thresholds() {
    }

    public static boolean cpu(SystemSample s) {
        return s.cpuPct() > CPU_PCT;
    }

    public static boolean memory(SystemSample s) {
        return s.memAvailMb() < MEM_AVAIL_FRACTION * s.memTotalMb()
                || s.pagesInPs() > PAGES_IN_PS
                || (s.commitLimitMb() > 0 && s.commitUsedMb() > COMMIT_FRACTION * s.commitLimitMb());
    }

    public static boolean disk(SystemSample s) {
        return s.diskBusyPct() > DISK_BUSY_PCT || s.diskQueue() >= DISK_QUEUE;
    }
}
