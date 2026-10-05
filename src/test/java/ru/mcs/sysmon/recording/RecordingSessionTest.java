package ru.mcs.sysmon.recording;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** These tests really sample the machine through OSHI, so they take a few seconds. */
class RecordingSessionTest {

    @TempDir
    Path dir;

    @Test
    void recordsStopsAndKeepsASecondRecorderOut() throws Exception {
        RecordingSession first = new RecordingSession(dir, 1, 5, 1 << 20, 10 << 20, null);
        first.start();
        assertTrue(RecordingSession.isLocked(dir));

        RecordingSession second = new RecordingSession(dir, 1, 5, 1 << 20, 10 << 20, null);
        assertThrows(RecordingSession.AlreadyRunningException.class, second::start);

        Thread.sleep(3500);
        first.stop();

        assertFalse(first.isRunning());
        assertTrue(first.status().samples() >= 2);
        assertFalse(RecordingSession.isLocked(dir));
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("system-")));
        }
    }

    @Test
    void finishesByItselfWhenTheDurationIsOver() throws Exception {
        RecordingSession session = new RecordingSession(dir, 1, 5, 1 << 20, 10 << 20, Duration.ofSeconds(2));
        AtomicReference<RecordingSession.Outcome> outcome = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        session.setFinishListener(o -> {
            outcome.set(o);
            done.countDown();
        });
        session.start();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(RecordingSession.Outcome.COMPLETED, outcome.get());
    }
}
