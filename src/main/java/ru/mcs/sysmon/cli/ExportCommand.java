package ru.mcs.sysmon.cli;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.Episodes;
import ru.mcs.sysmon.analysis.ProcessAggregator;
import ru.mcs.sysmon.analysis.ProcessAggregator.Stats;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.analysis.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The episodes command (a numbered list) and the export command (a zip with the chosen episodes). */
final class ExportCommand {
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private ExportCommand() {}

    static int runEpisodes(String[] argv) throws IOException {
        Map<String, String> o = Args.keyValues(argv);
        Path dir = Path.of(o.getOrDefault("in", Defaults.outDir().toString()));
        Lang lang = Lang.of(o.get("lang"));
        Recording rec = load(dir, lang);
        if (rec == null) {
            return 1;
        }
        List<Episode> list = Exporter.numbered(Episodes.detectAll(rec.system()));
        if (list.isEmpty()) {
            System.out.println(lang.t("No saturation episodes in ", "Эпизодов насыщения нет в ") + dir.toAbsolutePath());
            return 0;
        }
        System.out.println(lang.t("Saturation episodes in ", "Эпизоды насыщения в ")
                + dir.toAbsolutePath() + ": " + list.size());
        for (int i = 0; i < list.size(); i++) {
            Episode e = list.get(i);
            System.out.printf(Locale.ROOT, "%3d  %s - %s  %-8s %7s  %s%n", i + 1,
                    DATETIME.format(Instant.ofEpochMilli(e.startMs())),
                    TIME.format(Instant.ofEpochMilli(e.endMs())),
                    resourceName(e.resource(), lang),
                    e.durationMs() / 1000 + " " + lang.t("s", "с"),
                    mainProcesses(rec, e));
        }
        System.out.println(lang.t("Export: ", "Выгрузка: ") + "export --episodes=1,3");
        return 0;
    }

    static int runExport(String[] argv) throws IOException {
        Map<String, String> o = Args.keyValues(argv);
        Path dir = Path.of(o.getOrDefault("in", Defaults.outDir().toString()));
        Lang lang = Lang.of(o.get("lang"));
        long padMs = Durations.parse(o.getOrDefault("pad", "60s")).toMillis();
        Path zip = o.containsKey("out") ? Path.of(o.get("out")) : Exporter.defaultZip();
        Recording rec = load(dir, lang);
        if (rec == null) {
            return 1;
        }
        List<Episode> list = Exporter.numbered(Episodes.detectAll(rec.system()));
        if (list.isEmpty()) {
            System.err.println(lang.t("No saturation episodes in ", "Эпизодов насыщения нет в ") + dir.toAbsolutePath());
            return 1;
        }
        String selection = o.getOrDefault("episodes", "all");
        List<Exporter.Piece> pieces = selection.equals("all")
                ? Exporter.piecesFor(list, list)
                : Exporter.piecesByNumber(list, numbers(selection));
        int folders = Exporter.write(rec, pieces, padMs, lang, zip);
        System.out.println(lang.t("Saved: ", "Сохранено: ") + zip.toAbsolutePath()
                + " (" + lang.t("folders: ", "папок: ") + folders + ")");
        return 0;
    }

    private static Recording load(Path dir, Lang lang) throws IOException {
        if (!Files.isDirectory(dir)) {
            System.err.println(lang.t("Directory not found: ", "Каталог не найден: ") + dir.toAbsolutePath());
            return null;
        }
        Recording rec = Recording.load(dir);
        if (rec.system().isEmpty()) {
            System.err.println(lang.t("No system-*.csv samples in ", "Нет system-*.csv в ") + dir.toAbsolutePath());
            return null;
        }
        return rec;
    }

    private static Set<Integer> numbers(String text) {
        Set<Integer> result = new HashSet<>();
        try {
            for (String part : text.split(",")) {
                result.add(Integer.parseInt(part.trim()));
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("--episodes must be 'all' or numbers like 1,3, got " + text);
        }
        return result;
    }

    private static String mainProcesses(Recording rec, Episode e) {
        List<Stats> stats = ProcessAggregator.aggregate(rec.processes(), e.startMs(), e.endMs());
        return stats.stream()
                .filter(s -> !s.name().equals("(other)") && s.cpuAvg() > 0.5)
                .sorted(Comparator.comparingDouble(Stats::cpuAvg).reversed())
                .limit(3)
                .map(s -> String.format(Locale.ROOT, "%s %.1f%%", s.name(), s.cpuAvg()))
                .collect(Collectors.joining(", "));
    }

    private static String resourceName(Resource r, Lang lang) {
        return switch (r) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case DISK -> lang.t("Disk", "Диск");
        };
    }
}
