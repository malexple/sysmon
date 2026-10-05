package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.SystemSample;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public final class EpisodeDetector {

    private EpisodeDetector() {
    }

    /**
     * Consecutive breached samples form a run; runs closer than mergeGapMs are merged;
     * an episode must last at least minDurationMs (last - first + one sampling interval).
     */
    public static List<Episode> detect(List<SystemSample> all, Resource resource,
                                       Predicate<SystemSample> breached, long minDurationMs, long mergeGapMs) {
        int n = all.size();
        if (n == 0) {
            return List.of();
        }
        long interval = medianInterval(all);
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (breached.test(all.get(i))) {
                int j = i;
                while (j + 1 < n && breached.test(all.get(j + 1))) {
                    j++;
                }
                int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                if (last != null && all.get(i).tsMs() - all.get(last[1]).tsMs() <= mergeGapMs) {
                    last[1] = j;
                } else {
                    runs.add(new int[]{i, j});
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        List<Episode> result = new ArrayList<>();
        for (int[] r : runs) {
            long start = all.get(r[0]).tsMs();
            long end = all.get(r[1]).tsMs();
            long duration = end - start + interval;
            if (duration >= minDurationMs) {
                result.add(new Episode(resource, start, end, duration, new ArrayList<>(all.subList(r[0], r[1] + 1))));
            }
        }
        return result;
    }

    public static long medianInterval(List<SystemSample> all) {
        if (all.size() < 2) {
            return 0;
        }
        long[] d = new long[all.size() - 1];
        for (int k = 0; k < d.length; k++) {
            d[k] = all.get(k + 1).tsMs() - all.get(k).tsMs();
        }
        Arrays.sort(d);
        return d[d.length / 2];
    }
}
