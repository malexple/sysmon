package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.ProcessSample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Folds all rows of the same name (every java, every vivaldi) into one series per sample,
 * then summarises the series over a time window.
 * CPU and I/O are averaged over ALL samples of the window (a process missing from the top list counts as 0),
 * memory is averaged over the samples where the process is present. Read and write are kept apart.
 */
public final class ProcessAggregator {

    public record Stats(String name, double cpuAvg, double cpuMax, double rssAvgMb, double rssSwingMb,
                        double readAvgKbps, double writeAvgKbps) {
    }

    private ProcessAggregator() {
    }

    public static List<Stats> aggregate(NavigableMap<Long, List<ProcessSample>> processes, long fromMs, long toMs) {
        NavigableMap<Long, List<ProcessSample>> window = processes.subMap(fromMs, true, toMs, true);
        int samples = window.size();
        if (samples == 0) {
            return List.of();
        }
        // acc: 0 cpuSum, 1 cpuMax, 2 rssSum, 3 rssCount, 4 rssMin, 5 rssMax, 6 readSum, 7 writeSum
        Map<String, double[]> acc = new HashMap<>();
        for (List<ProcessSample> rows : window.values()) {
            // perSample: 0 cpu, 1 rss, 2 read, 3 write
            Map<String, double[]> perSample = new HashMap<>();
            for (ProcessSample p : rows) {
                double[] t = perSample.computeIfAbsent(p.name(), k -> new double[4]);
                t[0] += p.cpuPct();
                t[1] += p.rssMb();
                t[2] += p.readKbps();
                t[3] += p.writeKbps();
            }
            for (Map.Entry<String, double[]> e : perSample.entrySet()) {
                double[] t = e.getValue();
                double[] a = acc.computeIfAbsent(e.getKey(),
                        k -> new double[]{0, 0, 0, 0, Double.MAX_VALUE, 0, 0, 0});
                a[0] += t[0];
                a[1] = Math.max(a[1], t[0]);
                a[2] += t[1];
                a[3]++;
                a[4] = Math.min(a[4], t[1]);
                a[5] = Math.max(a[5], t[1]);
                a[6] += t[2];
                a[7] += t[3];
            }
        }
        List<Stats> result = new ArrayList<>(acc.size());
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            result.add(new Stats(e.getKey(), a[0] / samples, a[1], a[2] / a[3], a[5] - a[4],
                    a[6] / samples, a[7] / samples));
        }
        return result;
    }
}
