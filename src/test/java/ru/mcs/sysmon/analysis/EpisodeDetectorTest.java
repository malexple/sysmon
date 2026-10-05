package ru.mcs.sysmon.analysis;

import org.junit.jupiter.api.Test;
import ru.mcs.sysmon.model.SystemSample;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeDetectorTest {

    private static final long STEP = 5_000;

    /** One sample per 5 s; hot[i] == true means CPU 95%, else 10%. */
    private static List<SystemSample> series(boolean... hot) {
        List<SystemSample> list = new ArrayList<>();
        for (int i = 0; i < hot.length; i++) {
            list.add(new SystemSample(i * STEP, hot[i] ? 95 : 10, 8, 32000, 16000, 10000, 40000, 0, 0, 0, 0, 0, 0));
        }
        return list;
    }

    private static List<Episode> detect(List<SystemSample> s) {
        return EpisodeDetector.detect(s, Resource.CPU, Thresholds::cpu, 30_000, 30_000);
    }

    @Test
    void shortBurstIsNotAnEpisode() {
        assertTrue(detect(series(false, true, true, false, false)).isEmpty());
    }

    @Test
    void sustainedLoadIsOneEpisodeWithIntervalAddedToDuration() {
        List<Episode> e = detect(series(false, true, true, true, true, true, true, false));
        assertEquals(1, e.size());
        assertEquals(1 * STEP, e.get(0).startMs());
        assertEquals(6 * STEP, e.get(0).endMs());
        assertEquals(6 * STEP, e.get(0).durationMs());
    }

    @Test
    void closeRunsAreMergedButFarRunsAreNot() {
        boolean[] far = new boolean[28];
        for (int i = 0; i <= 6; i++) {
            far[i] = true;
            far[20 + i] = true;
        }
        assertEquals(2, detect(series(far)).size());
    }
}
