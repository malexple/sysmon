package ru.mcs.sysmon.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class TimeAxis {

    private static final long[] STEPS = {
            1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000,
            1_800_000, 3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000
    };
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DHM = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private TimeAxis() {
    }

    /** Tick times aligned to local clock multiples, at most about maxTicks of them. */
    static long[] ticks(long from, long to, int maxTicks) {
        long span = Math.max(1, to - from);
        long step = STEPS[STEPS.length - 1];
        for (long candidate : STEPS) {
            if (span / candidate <= maxTicks) {
                step = candidate;
                break;
            }
        }
        long offset = ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(from)).getTotalSeconds() * 1000L;
        long first = Math.floorDiv(from + offset, step) * step - offset;
        if (first < from) {
            first += step;
        }
        List<Long> list = new ArrayList<>();
        for (long t = first; t <= to; t += step) {
            list.add(t);
        }
        return list.stream().mapToLong(Long::longValue).toArray();
    }

    static String format(long ts, long spanMs) {
        DateTimeFormatter f = spanMs <= 15 * 60_000L ? HMS : spanMs <= 36 * 3_600_000L ? HM : DHM;
        return f.format(Instant.ofEpochMilli(ts));
    }
}
