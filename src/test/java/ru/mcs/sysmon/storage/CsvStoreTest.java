package ru.mcs.sysmon.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.sysmon.model.ProcessSample;
import ru.mcs.sysmon.model.Sample;
import ru.mcs.sysmon.model.SystemSample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvStoreTest {

    @TempDir
    Path dir;

    private static Sample sample(long ts) {
        SystemSample s = new SystemSample(ts, 12.5, 16, 16000, 4000, 12000, 20000, 1, 2, 3, 0.5, 10, 20);
        return new Sample(s, List.of(new ProcessSample(1, "idea64.exe", 1, 3.25, 2000, 1.5, 2.5)));
    }

    private List<Path> files(String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    @Test
    void rotatesBySizeAndEveryFileStartsWithHeader() throws IOException {
        try (CsvStore store = new CsvStore(dir, 600, 1_000_000)) {
            for (int i = 0; i < 100; i++) {
                store.write(sample(i));
            }
        }
        List<Path> sys = files("system-");
        assertTrue(sys.size() > 1, "expected several system files");
        for (Path f : sys) {
            assertTrue(Files.size(f) <= 600);
            assertEquals(SystemSample.HEADER, Files.readAllLines(f).get(0));
        }
        for (Path f : files("process-")) {
            assertEquals(ProcessSample.HEADER, Files.readAllLines(f).get(0));
        }
    }

    @Test
    void deletesOldestFilesWhenTotalCapExceeded() throws IOException {
        long cap = 3000;
        try (CsvStore store = new CsvStore(dir, 600, cap)) {
            for (int i = 0; i < 500; i++) {
                store.write(sample(i));
            }
        }
        long total = 0;
        for (String prefix : List.of("system-", "process-")) {
            for (Path f : files(prefix)) {
                total += Files.size(f);
            }
        }
        assertTrue(total <= cap + 2 * 600, "total " + total + " must stay near the cap");
        String firstSurvivingLine = Files.readAllLines(files("system-").stream().sorted().findFirst().orElseThrow()).get(1);
        assertTrue(Long.parseLong(firstSurvivingLine.split(",")[0]) > 0, "oldest samples must be gone");
    }

    @Test
    void csvUsesDotAsDecimalSeparatorWhateverTheLocale() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"));
            String line = new ProcessSample(7, "a", 1, 22.74, 100, 0, 0).toCsv(1);
            assertEquals(8, line.split(",").length);
            assertTrue(line.contains("22.74"));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }
}
