package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.ProcessSample;
import ru.mcs.sysmon.model.SystemSample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Everything recorded in one directory: system samples by time and process rows grouped by sample timestamp. */
public record Recording(List<SystemSample> system, NavigableMap<Long, List<ProcessSample>> processes,
                        int skippedLines) {

    /** Root plus two levels below it: enough for an unpacked export (root/archive/episode). */
    private static final int MAX_DEPTH = 3;

    public static Recording load(Path dir) throws IOException {
        NavigableMap<Long, SystemSample> system = new TreeMap<>();
        NavigableMap<Long, List<ProcessSample>> processes = new TreeMap<>();
        int skipped = 0;
        for (Path d : directories(dir)) {
            // One folder is one recording; folders of an export overlap by the padding,
            // so a sample time already seen in an earlier folder is skipped.
            NavigableMap<Long, List<ProcessSample>> local = new TreeMap<>();
            for (Path f : files(d, "system-*.csv")) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("ts_ms")) {
                        continue;
                    }
                    SystemSample s = parseSystem(line);
                    if (s == null) {
                        skipped++;
                    } else {
                        system.putIfAbsent(s.tsMs(), s);
                    }
                }
            }
            for (Path f : files(d, "process-*.csv")) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("ts_ms")) {
                        continue;
                    }
                    String[] c = line.split(",", -1);
                    try {
                        if (c.length != 8) {
                            throw new NumberFormatException();
                        }
                        long ts = Long.parseLong(c[0]);
                        local.computeIfAbsent(ts, k -> new ArrayList<>()).add(new ProcessSample(
                                Integer.parseInt(c[1]), c[2], Integer.parseInt(c[3]),
                                Double.parseDouble(c[4]), Long.parseLong(c[5]),
                                Double.parseDouble(c[6]), Double.parseDouble(c[7])));
                    } catch (NumberFormatException e) {
                        skipped++;
                    }
                }
            }
            for (Map.Entry<Long, List<ProcessSample>> e : local.entrySet()) {
                processes.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return new Recording(new ArrayList<>(system.values()), processes, skipped);
    }

    private static List<Path> directories(Path root) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                dirs.add(d);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE; // an unreadable folder is just not a recording
            }
        });
        dirs.sort(Comparator.comparing(Path::toString));
        return dirs;
    }

    private static SystemSample parseSystem(String line) {
        String[] c = line.split(",", -1);
        if (c.length != 13) {
            return null;
        }
        try {
            return new SystemSample(Long.parseLong(c[0]), Double.parseDouble(c[1]), Integer.parseInt(c[2]),
                    Long.parseLong(c[3]), Long.parseLong(c[4]), Long.parseLong(c[5]), Long.parseLong(c[6]),
                    Double.parseDouble(c[7]), Double.parseDouble(c[8]), Double.parseDouble(c[9]),
                    Double.parseDouble(c[10]), Double.parseDouble(c[11]), Double.parseDouble(c[12]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> files(Path dir, String glob) throws IOException {
        List<Path> list = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            ds.forEach(list::add);
        }
        list.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return list;
    }
}
