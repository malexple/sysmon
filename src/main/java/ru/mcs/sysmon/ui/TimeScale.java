package ru.mcs.sysmon.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps real time to display time. With compression every long gap without samples is squeezed
 * into a narrow stripe; without it the mapping is the identity.
 */
final class TimeScale {

    private final boolean compress;
    private final long keep;
    private final long[] from;
    private final long[] to;

    TimeScale(long[] ts, long interval, boolean compress) {
        this.compress = compress;
        this.keep = 4 * Math.max(1, interval);
        List<long[]> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < ts.length; i++) {
            long start = ts[i] + interval;
            if (ts[i + 1] - start > keep) {
                gaps.add(new long[]{start, ts[i + 1]});
            }
        }
        this.from = new long[gaps.size()];
        this.to = new long[gaps.size()];
        for (int i = 0; i < gaps.size(); i++) {
            from[i] = gaps.get(i)[0];
            to[i] = gaps.get(i)[1];
        }
    }

    boolean compressing() {
        return compress;
    }

    double map(long t) {
        if (!compress) {
            return t;
        }
        double removed = 0;
        for (int i = 0; i < from.length; i++) {
            if (t <= from[i]) {
                break;
            }
            long len = to[i] - from[i];
            if (t >= to[i]) {
                removed += len - keep;
            } else {
                return from[i] - removed + (t - from[i]) * ((double) keep / len);
            }
        }
        return t - removed;
    }

    long unmap(double v) {
        if (!compress) {
            return Math.round(v);
        }
        double removed = 0;
        for (int i = 0; i < from.length; i++) {
            long len = to[i] - from[i];
            double gapStart = from[i] - removed;
            if (v <= gapStart) {
                break;
            }
            if (v < gapStart + keep) {
                return Math.round(from[i] + (v - gapStart) * len / keep);
            }
            removed += len - keep;
        }
        return Math.round(v + removed);
    }

    boolean insideGap(long t) {
        for (int i = 0; i < from.length; i++) {
            if (t > from[i] && t < to[i]) {
                return true;
            }
        }
        return false;
    }

    /** First sample time after every compressible gap. */
    long[] gapEnds() {
        return to.clone();
    }
}
