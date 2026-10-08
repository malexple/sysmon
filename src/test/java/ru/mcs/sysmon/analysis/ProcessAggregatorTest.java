package ru.mcs.sysmon.analysis;

import org.junit.jupiter.api.Test;
import ru.mcs.sysmon.analysis.ProcessAggregator.Stats;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expected values were computed independently in Python from the real 2-minute recording in the test resources. */
class ProcessAggregatorTest {

    private static Stats byName(List<Stats> all, String name) {
        return all.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void keepsReadAndWriteApart() throws Exception {
        Recording rec = Recording.load(Path.of(getClass().getResource("/sample").toURI()));
        List<Stats> all = ProcessAggregator.aggregate(rec.processes(), 0, Long.MAX_VALUE);

        Stats antivirus = byName(all, "MsMpEng");
        assertEquals(239.42, antivirus.readAvgKbps(), 0.01);
        assertEquals(4.17, antivirus.writeAvgKbps(), 0.01);
        assertTrue(antivirus.readAvgKbps() > 10 * antivirus.writeAvgKbps(), "a scanner mostly reads");

        Stats browser = byName(all, "vivaldi");
        assertEquals(126.71, browser.readAvgKbps(), 0.01);
        assertEquals(145.15, browser.writeAvgKbps(), 0.01);
        assertTrue(browser.writeAvgKbps() > browser.readAvgKbps(), "a browser here writes more than it reads");

        Stats ide = byName(all, "idea64");
        assertEquals(19.39, ide.readAvgKbps(), 0.01);
        assertEquals(3.86, ide.writeAvgKbps(), 0.01);
    }
}
