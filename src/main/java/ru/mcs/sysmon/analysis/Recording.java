package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.ProcessSample;
import ru.mcs.sysmon.model.SystemSample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Everything recorded in one directory: system samples by time and process rows grouped by sample timestamp. */
public record Recording(List<SystemSample> system, NavigableMap<Long, List<ProcessSample>> processes,
                        int skippedLines) {

    public static Recording load(Path dir) throws IOException {
        List<SystemSample> system = new ArrayList<>();
        NavigableMap<Long, List<ProcessSample>> processes = new TreeMap<>();
        int skipped = 0;

        for (Path f : files(dir, "system-*.csv")) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("ts_ms")) {
                    continue;
                }
                SystemSample s = parseSystem(line);
                if (s == null) {
                    skipped++;
                } else {
                    system.add(s);
                }
            }
        }
        for (Path f : files(dir, "process-*.csv")) {
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
                    processes.computeIfAbsent(ts, k -> new ArrayList<>()).add(new ProcessSample(
                            Integer.parseInt(c[1]), c[2], Integer.parseInt(c[3]), Double.parseDouble(c[4]),
                            Long.parseLong(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7])));
                } catch (NumberFormatException e) {
                    skipped++;
                }
            }
        }
        system.sort(Comparator.comparingLong(SystemSample::tsMs));
        return new Recording(system, processes, skipped);
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
