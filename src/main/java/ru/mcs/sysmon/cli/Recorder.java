package ru.mcs.sysmon.cli;

import ru.mcs.sysmon.collector.Sampler;
import ru.mcs.sysmon.collector.TopN;
import ru.mcs.sysmon.model.Sample;
import ru.mcs.sysmon.model.SystemSample;
import ru.mcs.sysmon.storage.CsvStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class Recorder {

    private Recorder() {
    }

    static int run(Args a) throws IOException, InterruptedException {
        Files.createDirectories(a.out());
        try (FileChannel ch = FileChannel.open(a.out().resolve(".recorder.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = tryLock(ch)) {
            if (lock == null) {
                System.err.println("Another recorder is already writing to " + a.out().toAbsolutePath());
                return 2;
            }
            record(a);
            return 0;
        }
    }

    private static void record(Args a) throws IOException, InterruptedException {
        CountDownLatch stop = new CountDownLatch(1);
        Thread main = Thread.currentThread();
        Thread hook = new Thread(() -> {
            stop.countDown();
            try {
                main.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "sysmon-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        long deadline = a.duration() == null ? Long.MAX_VALUE : System.nanoTime() + a.duration().toNanos();
        try (CsvStore store = new CsvStore(a.out(), a.maxFileBytes(), a.maxTotalBytes())) {
            Sampler sampler = new Sampler();
            System.out.printf("Recording every %ds into %s (top %d, file <= %d MB, total <= %d MB%s). Ctrl+C to stop.%n",
                    a.intervalSec(), a.out().toAbsolutePath(), a.top(),
                    a.maxFileBytes() >> 20, a.maxTotalBytes() >> 20,
                    a.duration() == null ? "" : ", stops after " + a.duration());
            int n = 0;
            while (System.nanoTime() < deadline) {
                if (stop.await(a.intervalSec(), TimeUnit.SECONDS)) {
                    break;
                }
                Sample s = sampler.sample();
                store.write(new Sample(s.system(), TopN.select(s.processes(), a.top())));
                if (++n % 6 == 1) {
                    SystemSample x = s.system();
                    System.out.printf(Locale.ROOT, "[%d] cpu %.0f%%  mem avail %d/%d MB  disk busy %.0f%% queue %.1f%n",
                            n, x.cpuPct(), x.memAvailMb(), x.memTotalMb(), x.diskBusyPct(), x.diskQueue());
                }
            }
        }
        System.out.println("Stopped. Files are in " + a.out().toAbsolutePath());
    }

    private static FileLock tryLock(FileChannel ch) throws IOException {
        try {
            return ch.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }
}
