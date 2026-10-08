package ru.mcs.sysmon.cli;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.model.ProcessSample;
import ru.mcs.sysmon.model.SystemSample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A zip with the chosen episodes or an interval: one folder per piece, each folder
 * is a small recording that sysmon opens by itself. Lives in cli next to Lang so that
 * both the console commands and the window can use it.
 */
public final class Exporter {

    /** Context kept before and after every piece. */
    public static final long PAD_MS = 60_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** One folder of the archive: its name, what it is and the time range without the padding. */
    public record Piece(String folder, String kind, long fromMs, long toMs, long durationMs) {}

    private Exporter() {}

    /** Episodes in chronological order; every number (folders, --episodes) refers to this order. */
    public static List<Episode> numbered(List<Episode> episodes) {
        List<Episode> list = new ArrayList<>(episodes);
        list.sort(Comparator.comparingLong(Episode::startMs).thenComparing(Episode::resource));
        return list;
    }

    public static Piece episodePiece(int number, Episode e) {
        String kind = e.resource().name().toLowerCase(Locale.ROOT);
        String folder = "episode-" + number + "-" + kind + "-" + CLOCK.format(Instant.ofEpochMilli(e.startMs()));
        return new Piece(folder, kind, e.startMs(), e.endMs(), e.durationMs());
    }

    public static Piece intervalPiece(long fromMs, long toMs) {
        String folder = "custom-interval-" + CLOCK.format(Instant.ofEpochMilli(fromMs));
        return new Piece(folder, "custom", fromMs, toMs, toMs - fromMs);
    }

    /** Pieces for the chosen episodes; numbers come from the chronological order of all of them. */
    public static List<Piece> piecesFor(List<Episode> all, Collection<Episode> chosen) {
        Set<Episode> picked = Collections.newSetFromMap(new IdentityHashMap<>());
        picked.addAll(chosen);
        List<Episode> ordered = numbered(all);
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (picked.contains(ordered.get(i))) {
                pieces.add(episodePiece(i + 1, ordered.get(i)));
            }
        }
        return pieces;
    }

    /** Pieces for episode numbers as printed by the episodes command. */
    public static List<Piece> piecesByNumber(List<Episode> all, Set<Integer> numbers) {
        List<Episode> ordered = numbered(all);
        List<Piece> pieces = new ArrayList<>();
        for (int n : new TreeSet<>(numbers)) {
            if (n < 1 || n > ordered.size()) {
                throw new IllegalArgumentException("No episode " + n + ", there are " + ordered.size());
            }
            pieces.add(episodePiece(n, ordered.get(n - 1)));
        }
        return pieces;
    }

    /** The window's rule: episodes picked in the list, else the interval dragged on the strips, else all episodes. */
    public static List<Piece> choose(List<Episode> all, List<Episode> picked,
                                     boolean hasInterval, long fromMs, long toMs) {
        if (!picked.isEmpty()) {
            return piecesFor(all, picked);
        }
        if (hasInterval) {
            return List.of(intervalPiece(fromMs, toMs));
        }
        return piecesFor(all, all);
    }

    public static Path defaultZip() {
        return Defaults.outDir().resolve("sysmon-episodes-" + STAMP.format(LocalDateTime.now()) + ".zip");
    }

    /**
     * Writes the archive and returns how many folders it holds. Pieces without any sample are skipped;
     * if nothing is left, throws before the file is created.
     */
    public static int write(Recording rec, List<Piece> pieces, long padMs, Lang lang, Path zip)
            throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        NavigableMap<Long, SystemSample> systemUnion = new TreeMap<>();
        NavigableMap<Long, List<ProcessSample>> processUnion = new TreeMap<>();
        StringBuilder index = new StringBuilder("folder,resource,start,end,duration_s\n");
        int folders = 0;
        for (Piece p : pieces) {
            long from = p.fromMs() - padMs;
            long to = p.toMs() + padMs;
            List<SystemSample> system = rec.system().stream()
                    .filter(s -> s.tsMs() >= from && s.tsMs() <= to).toList();
            if (system.isEmpty()) {
                continue;
            }
            StringBuilder systemCsv = new StringBuilder(SystemSample.HEADER).append('\n');
            for (SystemSample s : system) {
                systemCsv.append(s.toCsv()).append('\n');
                systemUnion.put(s.tsMs(), s);
            }
            StringBuilder processCsv = new StringBuilder(ProcessSample.HEADER).append('\n');
            for (Map.Entry<Long, List<ProcessSample>> e : rec.processes().subMap(from, true, to, true).entrySet()) {
                for (ProcessSample q : e.getValue()) {
                    processCsv.append(q.toCsv(e.getKey())).append('\n');
                }
                processUnion.put(e.getKey(), e.getValue());
            }
            entries.put(p.folder() + "/system-export.csv", systemCsv.toString());
            entries.put(p.folder() + "/process-export.csv", processCsv.toString());
            index.append(String.join(",", p.folder(), p.kind(),
                            DATETIME.format(Instant.ofEpochMilli(p.fromMs())),
                            DATETIME.format(Instant.ofEpochMilli(p.toMs())),
                            Long.toString(p.durationMs() / 1000)))
                    .append('\n');
            folders++;
        }
        if (folders == 0) {
            throw new IllegalStateException(
                    lang.t("No samples in the chosen range.", "В выбранном диапазоне нет замеров."));
        }
        entries.put("episodes.csv", index.toString());
        entries.put("summary.txt", ReportCommand.render(
                new Recording(new ArrayList<>(systemUnion.values()), processUnion, 0), lang, 10));

        Path parent = zip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return folders;
    }
}
