package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.EpisodeDetector;
import ru.mcs.sysmon.analysis.ProcessAggregator;
import ru.mcs.sysmon.analysis.ProcessAggregator.Stats;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.analysis.Resource;
import ru.mcs.sysmon.cli.Lang;
import ru.mcs.sysmon.model.SystemSample;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** One short paragraph that says what happened in the whole recording or in the selected interval. */
final class Verdict {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private Verdict() {
    }

    static String text(Recording rec, List<Episode> episodes, long from, long to, boolean whole, Lang lang) {
        List<SystemSample> sys = rec.system();
        if (whole) {
            long interval = EpisodeDetector.medianInterval(sys);
            long span = sys.get(sys.size() - 1).tsMs() - sys.get(0).tsMs();
            long recorded = sys.size() * interval;
            String samples = lang.t("Samples", "Замеров");
            String head;
            if (span - recorded > Math.max(120_000, 3 * interval)) {
                head = f("%s: %d, %s %s %s %s. ", samples, sys.size(),
                        lang.t("recorded", "записано"), duration(recorded, lang),
                        lang.t("of", "из"), duration(span, lang));
            } else {
                head = f("%s: %d, %s. ", samples, sys.size(), duration(span, lang));
            }
            if (episodes.isEmpty()) {
                return head + lang.t("No saturation episodes found. Drag over the strips to inspect an interval.",
                        "Эпизодов насыщения не найдено. Выделите мышью интервал на полосах, чтобы изучить его.");
            }
            String list = episodes.stream().limit(4)
                    .map(e -> resourceName(e.resource(), lang) + " " + TIME.format(Instant.ofEpochMilli(e.startMs()))
                            + " (" + duration(e.durationMs(), lang) + ")")
                    .collect(Collectors.joining("; "));
            return head + lang.t("Saturation episodes: ", "Эпизодов насыщения: ") + episodes.size()
                    + " - " + list + (episodes.size() > 4 ? "; ..." : "") + ". "
                    + lang.t("Pick one in the list or drag over the strips.",
                    "Выберите эпизод в списке или выделите интервал на полосах.");
        }

        List<SystemSample> w = sys.stream().filter(s -> s.tsMs() >= from && s.tsMs() <= to).toList();
        if (w.isEmpty()) {
            return lang.t("No samples in the selected interval.", "В выбранном интервале нет замеров.");
        }
        double cpuMax = w.stream().mapToDouble(SystemSample::cpuPct).max().orElse(0);
        double freeMin = w.stream().mapToDouble(s -> s.memAvailMb()).min().orElse(0);
        double pagesMax = w.stream().mapToDouble(SystemSample::pagesInPs).max().orElse(0);
        double busyMax = w.stream().mapToDouble(SystemSample::diskBusyPct).max().orElse(0);

        StringBuilder sb = new StringBuilder(f("%s - %s (%s): ", TIME.format(Instant.ofEpochMilli(from)),
                TIME.format(Instant.ofEpochMilli(to)), duration(to - from, lang)));
        sb.append(f(lang.t("CPU max %.0f%%, free memory min %.0f MB, page-ins max %.0f/s, disk busy max %.0f%%. ",
                "CPU макс %.0f%%, свободно памяти мин %.0f МБ, page-in макс %.0f/с, диск занят макс %.0f%%. "),
                cpuMax, freeMin, pagesMax, busyMax));

        String overlapping = episodes.stream().filter(e -> e.startMs() <= to && e.endMs() >= from)
                .map(e -> resourceName(e.resource(), lang)).distinct().collect(Collectors.joining(", "));
        if (!overlapping.isEmpty()) {
            sb.append(lang.t("Saturation here: ", "Насыщение здесь: ")).append(overlapping).append(". ");
        }

        List<Stats> named = ProcessAggregator.aggregate(rec.processes(), from, to).stream()
                .filter(s -> !s.name().equals("(other)")).toList();
        String cpu = named.stream().filter(s -> s.cpuAvg() >= 0.5)
                .sorted(Comparator.comparingDouble(Stats::cpuAvg).reversed()).limit(3)
                .map(s -> f("%s %.1f%%", s.name(), s.cpuAvg())).collect(Collectors.joining(", "));
        if (!cpu.isEmpty()) {
            sb.append(lang.t("Most CPU: ", "Больше всего CPU: ")).append(cpu).append(". ");
        }
        String io = named.stream().filter(s -> s.ioAvgKbps() >= 1000)
                .sorted(Comparator.comparingDouble(Stats::ioAvgKbps).reversed()).limit(2)
                .map(s -> f("%s %.1f MB/s", s.name(), s.ioAvgKbps() / 1024.0))
                .collect(Collectors.joining(", "));
        if (!io.isEmpty()) {
            sb.append(lang.t("Most I/O: ", "Больше всего ввода-вывода: ")).append(io).append(". ");
        }
        named.stream().max(Comparator.comparingDouble(Stats::rssSwingMb))
                .filter(s -> s.rssSwingMb() >= 300)
                .ifPresent(s -> sb.append(lang.t("Biggest memory change: ", "Сильнее всего изменилась память: "))
                        .append(f("%s %.0f MB", s.name(), s.rssSwingMb())).append(". "));
        return sb.toString().trim();
    }

    private static String resourceName(Resource r, Lang lang) {
        return switch (r) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case DISK -> lang.t("Disk", "Диск");
        };
    }

    private static String duration(long ms, Lang lang) {
        long sec = ms / 1000;
        if (sec < 120) {
            return sec + " " + lang.t("s", "с");
        }
        if (sec < 7200) {
            return (sec / 60) + " " + lang.t("min", "мин");
        }
        return f("%.1f", sec / 3600.0) + " " + lang.t("h", "ч");
    }

    private static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }
}
