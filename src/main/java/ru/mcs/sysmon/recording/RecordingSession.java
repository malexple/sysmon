package ru.mcs.sysmon.recording;

import ru.mcs.sysmon.collector.Sampler;
import ru.mcs.sysmon.collector.TopN;
import ru.mcs.sysmon.model.Sample;
import ru.mcs.sysmon.storage.CsvStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One recording into one directory, running on its own thread. Used by the console command and by the window.
 * A lock file keeps a second recorder (in this or another process) out of the same directory.
 */
public final class RecordingSession {

    public enum Outcome { COMPLETED, STOPPED, FAILED }

    public record Status(long elapsedMs, long plannedMs, long samples, long bytes) {
    }

    public static final class AlreadyRunningException extends IOException {
        public AlreadyRunningException(Path dir) {
            super("Another recorder is already writing to " + dir.toAbsolutePath());
        }
    }

    private final Path dir;
    private final int intervalSec;
    private final int top;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final Duration duration;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicLong samples = new AtomicLong();

    private volatile Consumer<Sample> sampleListener = s -> {
    };
    private volatile Consumer<Outcome> finishListener = o -> {
    };
    private volatile long startNanos;
    private volatile long endNanos;
    private volatile Throwable failure;
    private Thread thread;
    private FileChannel lockChannel;
    private FileLock lock;

    public RecordingSession(Path dir, int intervalSec, int top, long maxFileBytes, long maxTotalBytes,
                            Duration duration) {
        this.dir = dir;
        this.intervalSec = intervalSec;
        this.top = top;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.duration = duration;
    }

    /** Called on the recorder thread after every written sample. */
    public void setSampleListener(Consumer<Sample> listener) {
        this.sampleListener = listener;
    }

    /** Called on the recorder thread when recording ends, after the files are closed and the lock is released. */
    public void setFinishListener(Consumer<Outcome> listener) {
        this.finishListener = listener;
    }

    /** True if some recorder (possibly this process) holds the lock of the directory. */
    public static boolean isLocked(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(dir.resolve(".recorder.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock l = ch.tryLock()) {
            return l == null;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized void start() throws IOException {
        if (thread != null) {
            throw new IllegalStateException("Already started");
        }
        Files.createDirectories(dir);
        lockChannel = FileChannel.open(dir.resolve(".recorder.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            throw new AlreadyRunningException(dir);
        }
        startNanos = System.nanoTime();
        thread = new Thread(this::loop, "sysmon-recorder");
        thread.start();
    }

    public void requestStop() {
        stopSignal.countDown();
    }

    /** Requests a stop and waits (up to 10 s) for the recorder thread to finish. */
    public void stop() {
        requestStop();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void awaitFinish() throws InterruptedException {
        Thread t = thread;
        if (t != null) {
            t.join();
        }
    }

    public boolean isRunning() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    public Throwable failure() {
        return failure;
    }

    public Status status() {
        long end = endNanos != 0 ? endNanos : System.nanoTime();
        long elapsed = startNanos == 0 ? 0 : (end - startNanos) / 1_000_000;
        return new Status(elapsed, duration == null ? -1 : duration.toMillis(), samples.get(), dirBytes());
    }

    private void loop() {
        Outcome outcome = Outcome.FAILED;
        try {
            try (CsvStore store = new CsvStore(dir, maxFileBytes, maxTotalBytes)) {
                Sampler sampler = new Sampler();
                long deadline = duration == null ? Long.MAX_VALUE : System.nanoTime() + duration.toNanos();
                boolean stopped = false;
                while (System.nanoTime() < deadline) {
                    if (stopSignal.await(intervalSec, TimeUnit.SECONDS)) {
                        stopped = true;
                        break;
                    }
                    Sample s = sampler.sample();
                    store.write(new Sample(s.system(), TopN.select(s.processes(), top)));
                    samples.incrementAndGet();
                    sampleListener.accept(s);
                }
                outcome = stopped ? Outcome.STOPPED : Outcome.COMPLETED;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = Outcome.STOPPED;
        } catch (Throwable t) {
            failure = t;
        } finally {
            endNanos = System.nanoTime();
            releaseLock();
            finishListener.accept(outcome);
        }
    }

    private void releaseLock() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // the lock disappears with the process anyway
        }
    }

    private long dirBytes() {
        long total = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "{system,process}-*.csv")) {
            for (Path p : ds) {
                try {
                    total += Files.size(p);
                } catch (IOException ignored) {
                    // file removed by the quota cleanup meanwhile
                }
            }
        } catch (IOException ignored) {
            // directory not readable yet
        }
        return total;
    }
}
