package ru.mcs.sysmon.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeScaleTest {

    @Test
    void isTheIdentityWithoutCompression() {
        TimeScale s = new TimeScale(new long[]{0, 1000, 2000}, 1000, false);
        assertEquals(1500, s.map(1500), 1e-9);
        assertEquals(1500, s.unmap(1500));
    }

    @Test
    void squeezesLongGapsAndInvertsExactly() {
        long[] ts = {0, 10_000, 20_000, 3_620_000, 3_630_000};
        TimeScale s = new TimeScale(ts, 10_000, true);

        assertEquals(40_000, s.map(3_620_000) - s.map(30_000), 1e-6);
        assertEquals(80_000, s.map(3_630_000), 1e-6);
        for (long t : ts) {
            assertEquals(t, s.unmap(s.map(t)));
        }
        assertTrue(s.insideGap(1_000_000));
        assertFalse(s.insideGap(10_000));
    }

    @Test
    void ticksAvoidSqueezedGaps() {
        long[] ts = {0, 10_000, 20_000, 3_620_000, 3_630_000};
        TimeScale s = new TimeScale(ts, 10_000, true);
        for (long t : TimeAxis.visibleTicks(s, 0, 3_640_000, 20)) {
            assertFalse(s.insideGap(t));
        }
    }
}
