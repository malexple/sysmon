package ru.mcs.sysmon.storage;

import ru.mcs.sysmon.model.ProcessSample;
import ru.mcs.sysmon.model.Sample;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * system-*.csv and process-*.csv in one directory. Each file is rotated at maxFileBytes;
 * when the directory grows past maxTotalBytes the oldest closed files are deleted.
 */
public final class CsvStore implements Closeable {

    private final Path dir;
    private final long maxTotalBytes;
    private final RotatingCsv system;
    private final RotatingCsv process;

    public CsvStore(Path dir, long maxFileBytes, long maxTotalBytes) throws IOException {
        Files.createDirectories(dir);
        this.dir = dir;
        this.maxTotalBytes = maxTotalBytes;
        this.system = new RotatingCsv(dir, "system", ru.mcs.sysmon.model.SystemSample.HEADER,
                maxFileBytes, r -> enforceCap());
        this.process = new RotatingCsv(dir, "process", ProcessSample.HEADER,
                maxFileBytes, r -> enforceCap());
        enforceCap();
    }

    public void write(Sample sample) throws IOException {
        system.writeLine(sample.system().toCsv());
        long ts = sample.system().tsMs();
        for (ProcessSample p : sample.processes()) {
            process.writeLine(p.toCsv(ts));
        }
        system.flush();
        process.flush();
    }

    private void enforceCap() {
        try {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "{system,process}-*.csv")) {
                ds.forEach(files::add);
            }
            long total = 0;
            for (Path f : files) {
                total += Files.size(f);
            }
            files.sort(Comparator.comparing((Path f) -> f.getFileName().toString().substring(
                    f.getFileName().toString().indexOf('-') + 1)).thenComparing(Path::getFileName));
            for (Path f : files) {
                if (total <= maxTotalBytes) {
                    break;
                }
                if (f.equals(system.currentFile()) || f.equals(process.currentFile())) {
                    continue;
                }
                long len = Files.size(f);
                Files.delete(f);
                total -= len;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        system.close();
        process.close();
    }
}
