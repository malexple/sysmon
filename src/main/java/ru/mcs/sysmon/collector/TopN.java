package ru.mcs.sysmon.collector;

import ru.mcs.sysmon.model.ProcessSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Keeps the n most "significant" processes of a sample and folds the rest into one "(other)" row.
 * Significance = the largest of the process's shares of total CPU, total memory and total I/O,
 * so a memory hog with zero CPU is kept just like a CPU hog.
 */
public final class TopN {

    private TopN() {
    }

    public static List<ProcessSample> select(List<ProcessSample> all, int n) {
        if (all.size() <= n) {
            return all;
        }
        double sumCpu = 0, sumRss = 0, sumIo = 0;
        for (ProcessSample p : all) {
            sumCpu += p.cpuPct();
            sumRss += p.rssMb();
            sumIo += p.readKbps() + p.writeKbps();
        }
        final double c = sumCpu, r = sumRss, i = sumIo;
        ToDoubleFunction<ProcessSample> score = p -> Math.max(
                c > 0 ? p.cpuPct() / c : 0,
                Math.max(r > 0 ? p.rssMb() / r : 0,
                        i > 0 ? (p.readKbps() + p.writeKbps()) / i : 0));

        List<ProcessSample> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(score).reversed());

        List<ProcessSample> result = new ArrayList<>(sorted.subList(0, n));
        int procs = 0;
        double cpu = 0, rd = 0, wr = 0;
        long rss = 0;
        for (ProcessSample p : sorted.subList(n, sorted.size())) {
            procs += p.procs();
            cpu += p.cpuPct();
            rss += p.rssMb();
            rd += p.readKbps();
            wr += p.writeKbps();
        }
        result.add(new ProcessSample(-1, "(other)", procs, cpu, rss, rd, wr));
        return result;
    }
}
