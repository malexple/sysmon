package ru.mcs.sysmon.cli;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.EpisodeDetector;
import ru.mcs.sysmon.analysis.ProcessAggregator;
import ru.mcs.sysmon.analysis.ProcessAggregator.Stats;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.analysis.Resource;
import ru.mcs.sysmon.analysis.Thresholds;
import ru.mcs.sysmon.model.SystemSample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

final class ReportCommand {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private ReportCommand() {
    }

    static int run(String[] argv) throws IOException {
        Map<String, String> o = Args.keyValues(argv);
        Path dir = Path.of(o.getOrDefault("in", "samples"));
        Lang lang = Lang.of(o.get("lang"));
        int top = Integer.parseInt(o.getOrDefault("top", "10"));
        if (!Files.isDirectory(dir)) {
            System.err.println(lang.t("Directory not found: ", "Каталог не найден: ") + dir.toAbsolutePath());
            return 1;
        }
        Recording rec = Recording.load(dir);
        if (rec.system().isEmpty()) {
            System.err.println(lang.t("No system-*.csv samples in ", "Нет файлов system-*.csv в ") + dir.toAbsolutePath());
            return 1;
        }
        String text = render(rec, lang, top);
        System.out.print(text);
        if (o.containsKey("file")) {
            Files.writeString(Path.of(o.get("file")), text, StandardCharsets.UTF_8);
        }
        return 0;
    }

    static List<Episode> episodes(Recording rec) {
        List<SystemSample> s = rec.system();
        List<Episode> all = new ArrayList<>();
        all.addAll(EpisodeDetector.detect(s, Resource.CPU, Thresholds::cpu, Thresholds.MIN_EPISODE_MS, Thresholds.MERGE_GAP_MS));
        all.addAll(EpisodeDetector.detect(s, Resource.MEMORY, Thresholds::memory, Thresholds.MIN_EPISODE_MS, Thresholds.MERGE_GAP_MS));
        all.addAll(EpisodeDetector.detect(s, Resource.DISK, Thresholds::disk, Thresholds.MIN_EPISODE_MS, Thresholds.MERGE_GAP_MS));
        all.sort(Comparator.comparingLong(Episode::startMs));
        return all;
    }

    static String render(Recording rec, Lang lang, int top) {
        List<SystemSample> sys = rec.system();
        SystemSample first = sys.get(0);
        SystemSample last = sys.get(sys.size() - 1);
        long interval = EpisodeDetector.medianInterval(sys);
        StringBuilder sb = new StringBuilder();

        sb.append(lang.t("Report: ", "Отчёт: ")).append(DATE_TIME.format(Instant.ofEpochMilli(first.tsMs())))
                .append(" - ").append(DATE_TIME.format(Instant.ofEpochMilli(last.tsMs()))).append('\n');
        sb.append(f("%d %s, %s ~%d %s, %d %s, %.0f GB RAM%n", sys.size(), lang.t("samples", "замеров"),
                lang.t("interval", "интервал"), interval / 1000, lang.t("s", "с"),
                first.logicalCpus(), lang.t("logical CPUs", "логических CPU"), first.memTotalMb() / 1024.0));
        if (rec.skippedLines() > 0) {
            sb.append(f("%s: %d%n", lang.t("Skipped malformed lines", "Пропущено повреждённых строк"), rec.skippedLines()));
        }

        sb.append('\n').append(lang.t("== Machine (USE) ==", "== Машина (USE) ==")).append('\n');
        sb.append(f("%-26s %10s %10s %10s%n", "", "avg", "p95", "max"));
        stat(sb, lang.t("CPU, %", "CPU, %"), sys, SystemSample::cpuPct);
        stat(sb, lang.t("Memory used, %", "Память занята, %"), sys, s -> 100.0 - 100.0 * s.memAvailMb() / s.memTotalMb());
        stat(sb, lang.t("Commit, % of limit", "Commit, % от лимита"), sys,
                s -> s.commitLimitMb() > 0 ? 100.0 * s.commitUsedMb() / s.commitLimitMb() : 0);
        stat(sb, lang.t("Page-ins/s", "Page-in в секунду"), sys, SystemSample::pagesInPs);
        stat(sb, lang.t("Disk busy, %", "Диск занят, %"), sys, SystemSample::diskBusyPct);
        stat(sb, lang.t("Disk queue", "Очередь диска"), sys, SystemSample::diskQueue);
        stat(sb, lang.t("Disk read, KB/s", "Чтение диска, КБ/с"), sys, SystemSample::diskReadKbps);
        stat(sb, lang.t("Disk write, KB/s", "Запись диска, КБ/с"), sys, SystemSample::diskWriteKbps);

        sb.append('\n').append(lang.t("== Saturation episodes ==", "== Эпизоды насыщения ==")).append('\n');
        sb.append(f(lang.t("Thresholds: CPU > %.0f%%; memory: free < %.0f%% or page-ins > %.0f/s or commit > %.0f%%; disk: busy > %.0f%% or queue >= %.0f; min %d s%n",
                        "Пороги: CPU > %.0f%%; память: свободно < %.0f%% или page-in > %.0f/с или commit > %.0f%%; диск: занят > %.0f%% или очередь >= %.0f; минимум %d с%n"),
                Thresholds.CPU_PCT, Thresholds.MEM_AVAIL_FRACTION * 100, Thresholds.PAGES_IN_PS,
                Thresholds.COMMIT_FRACTION * 100, Thresholds.DISK_BUSY_PCT, Thresholds.DISK_QUEUE,
                Thresholds.MIN_EPISODE_MS / 1000));
        List<Episode> episodes = episodes(rec);
        if (episodes.isEmpty()) {
            sb.append(lang.t("No saturation episodes found.", "Эпизодов насыщения не найдено.")).append('\n');
        }
        for (Episode e : episodes) {
            sb.append(f("%n%s - %s (%d %s)  %s: %s%n", TIME.format(Instant.ofEpochMilli(e.startMs())),
                    TIME.format(Instant.ofEpochMilli(e.endMs())), e.durationMs() / 1000, lang.t("s", "с"),
                    resourceName(e.resource(), lang), details(e, lang)));
            List<Stats> stats = new ArrayList<>(ProcessAggregator.aggregate(rec.processes(), e.startMs(), e.endMs()));
            Comparator<Stats> order = switch (e.resource()) {
                case CPU -> Comparator.comparingDouble(Stats::cpuAvg).reversed();
                case MEMORY -> Comparator.comparingDouble(Stats::rssSwingMb).reversed();
                case DISK -> Comparator.comparingDouble(Stats::ioAvgKbps).reversed();
            };
            stats.sort(order);
            table(sb, lang, stats, 5, "  ");
        }

        List<Stats> all = new ArrayList<>(ProcessAggregator.aggregate(rec.processes(), first.tsMs(), last.tsMs()));
        all.sort(Comparator.comparingDouble(Stats::cpuAvg).reversed());
        sb.append('\n').append(lang.t("== Top processes by CPU ==", "== Топ процессов по CPU ==")).append('\n');
        table(sb, lang, all, top, "");
        all.sort(Comparator.comparingDouble(Stats::rssAvgMb).reversed());
        sb.append('\n').append(lang.t("== Top processes by memory ==", "== Топ процессов по памяти ==")).append('\n');
        table(sb, lang, all, top, "");
        sb.append('\n').append(lang.t(
                "Note: process I/O counts all I/O of the process (files, network, devices), not only disk; use the machine disk rows for disk load.",
                "Примечание: ввод-вывод процесса включает файлы, сеть и устройства, а не только диск; нагрузку на диск смотрите по строкам машины.")).append('\n');
        return sb.toString();
    }

    private static String details(Episode e, Lang lang) {
        List<SystemSample> s = e.samples();
        return switch (e.resource()) {
            case CPU -> f("%s %.0f%%, %s %.0f%%", lang.t("CPU max", "CPU макс"), max(s, SystemSample::cpuPct),
                    lang.t("avg", "средн."), avg(s, SystemSample::cpuPct));
            case MEMORY -> f("%s %.0f MB, %s %.0f/s, commit %s %.0f%%",
                    lang.t("free min", "свободно мин"), min(s, x -> (double) x.memAvailMb()),
                    lang.t("page-ins max", "page-in макс"), max(s, SystemSample::pagesInPs),
                    lang.t("max", "макс"), max(s, x -> x.commitLimitMb() > 0 ? 100.0 * x.commitUsedMb() / x.commitLimitMb() : 0));
            case DISK -> f("%s %.0f%%, %s %.1f, %s %.0f KB/s, %s %.0f KB/s",
                    lang.t("busy max", "занят макс"), max(s, SystemSample::diskBusyPct),
                    lang.t("queue max", "очередь макс"), max(s, SystemSample::diskQueue),
                    lang.t("read max", "чтение макс"), max(s, SystemSample::diskReadKbps),
                    lang.t("write max", "запись макс"), max(s, SystemSample::diskWriteKbps));
        };
    }

    private static String resourceName(Resource r, Lang lang) {
        return switch (r) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case DISK -> lang.t("Disk", "Диск");
        };
    }

    private static void table(StringBuilder sb, Lang lang, List<Stats> rows, int n, String indent) {
        sb.append(indent).append(f("%-24s %8s %8s %10s %10s %10s%n", lang.t("process", "процесс"),
                "CPU avg%", "CPU max%", "RSS avg MB", lang.t("RSS swing", "RSS размах"), "IO KB/s"));
        for (Stats s : rows.subList(0, Math.min(n, rows.size()))) {
            String name = s.name().length() > 24 ? s.name().substring(0, 24) : s.name();
            sb.append(indent).append(f("%-24s %8.1f %8.1f %10.0f %10.0f %10.0f%n", name,
                    s.cpuAvg(), s.cpuMax(), s.rssAvgMb(), s.rssSwingMb(), s.ioAvgKbps()));
        }
    }

    private static void stat(StringBuilder sb, String label, List<SystemSample> sys, ToDoubleFunction<SystemSample> fn) {
        double[] v = sys.stream().mapToDouble(fn).sorted().toArray();
        double avg = Arrays.stream(v).average().orElse(0);
        int idx = Math.max(0, Math.min(v.length - 1, (int) Math.ceil(0.95 * v.length) - 1));
        sb.append(f("%-26s %10.1f %10.1f %10.1f%n", label, avg, v[idx], v[v.length - 1]));
    }

    private static double max(List<SystemSample> s, ToDoubleFunction<SystemSample> fn) {
        return s.stream().mapToDouble(fn).max().orElse(0);
    }

    private static double min(List<SystemSample> s, ToDoubleFunction<SystemSample> fn) {
        return s.stream().mapToDouble(fn).min().orElse(0);
    }

    private static double avg(List<SystemSample> s, ToDoubleFunction<SystemSample> fn) {
        return s.stream().mapToDouble(fn).average().orElse(0);
    }

    private static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }
}
