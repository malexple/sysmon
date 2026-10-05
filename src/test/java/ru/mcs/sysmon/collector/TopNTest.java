package ru.mcs.sysmon.collector;

import org.junit.jupiter.api.Test;
import ru.mcs.sysmon.model.ProcessSample;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopNTest {

    @Test
    void keepsTopNAndFoldsRestIntoOther() {
        List<ProcessSample> all = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            all.add(new ProcessSample(i, "p" + i, 1, i * 0.1, 10, 1, 1));
        }
        all.add(new ProcessSample(99, "memhog", 1, 0.0, 5000, 0, 0));

        List<ProcessSample> r = TopN.select(all, 3);

        assertEquals(4, r.size());
        assertTrue(r.stream().anyMatch(p -> p.name().equals("memhog")), "memory hog must survive");
        ProcessSample other = r.get(3);
        assertEquals("(other)", other.name());
        assertEquals(18, other.procs());
        double cpuTotal = all.stream().mapToDouble(ProcessSample::cpuPct).sum();
        assertEquals(cpuTotal, r.stream().mapToDouble(ProcessSample::cpuPct).sum(), 1e-9);
    }

    @Test
    void returnsInputWhenSmallerThanN() {
        List<ProcessSample> all = List.of(new ProcessSample(1, "a", 1, 1, 1, 0, 0));
        assertEquals(all, TopN.select(all, 15));
    }
}
