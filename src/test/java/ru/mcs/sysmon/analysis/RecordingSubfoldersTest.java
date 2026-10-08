package ru.mcs.sysmon.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingSubfoldersTest {
    @TempDir
    Path root;

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, to.resolve(f.getFileName()));
            }
        }
    }

    private static int processRows(Recording rec) {
        return rec.processes().values().stream().mapToInt(List::size).sum();
    }

    @Test
    void readsSubfoldersAndMergesSamplesWithTheSameTime() throws Exception {
        Path sample = Path.of(getClass().getResource("/sample").toURI());
        copy(sample, root.resolve("a"));
        copy(sample, root.resolve("nested").resolve("b"));

        Recording merged = Recording.load(root);
        Recording single = Recording.load(sample);

        assertEquals(single.system().size(), merged.system().size());
        assertEquals(single.processes().size(), merged.processes().size());
        assertEquals(processRows(single), processRows(merged));
        assertEquals(0, merged.skippedLines());
    }
}
