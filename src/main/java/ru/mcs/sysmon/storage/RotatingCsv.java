package ru.mcs.sysmon.storage;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Appends lines to prefix-yyyyMMdd-HHmmss-NNN.csv and starts a new file (with header) when maxBytes is reached. */
final class RotatingCsv implements Closeable {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final String prefix;
    private final String header;
    private final long maxBytes;
    private final Consumer<RotatingCsv> afterRotate;
    private final String stamp = STAMP.format(LocalDateTime.now());

    private BufferedWriter out;
    private Path current;
    private long size;
    private int seq;

    RotatingCsv(Path dir, String prefix, String header, long maxBytes, Consumer<RotatingCsv> afterRotate) {
        this.dir = dir;
        this.prefix = prefix;
        this.header = header;
        this.maxBytes = maxBytes;
        this.afterRotate = afterRotate;
    }

    Path currentFile() {
        return current;
    }

    void writeLine(String line) throws IOException {
        long len = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (out == null || size + len > maxBytes) {
            rotate();
        }
        out.write(line);
        out.write('\n');
        size += len;
    }

    void flush() throws IOException {
        if (out != null) {
            out.flush();
        }
    }

    private void rotate() throws IOException {
        close();
        seq++;
        current = dir.resolve(String.format("%s-%s-%03d.csv", prefix, stamp, seq));
        out = Files.newBufferedWriter(current, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        out.write(header);
        out.write('\n');
        size = header.getBytes(StandardCharsets.UTF_8).length + 1L;
        afterRotate.accept(this);
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }
}
