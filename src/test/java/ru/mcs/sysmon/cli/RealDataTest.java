package ru.mcs.sysmon.cli;

import org.junit.jupiter.api.Test;
import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.ProcessAggregator;
import ru.mcs.sysmon.analysis.ProcessAggregator.Stats;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.analysis.Resource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real 2-minute recording (8 logical CPUs, 32 GB) taken on 2026-10-05; expected values were computed independently in Python. */
class RealDataTest {

    private Recording load() throws Exception {
        return Recording.load(Path.of(getClass().getResource("/sample").toURI()));
    }

    @Test
    void loadsEverythingWithoutSkippedLines() throws Exception {
        Recording rec = load();
        assertEquals(24, rec.system().size());
        assertEquals(24, rec.processes().size());
        assertEquals(0, rec.skippedLines());
    }

//    @Test
//    void findsOneMemoryEpisodeAndNothingElse() throws Exception {
//        List<Episode> episodes = ReportCommand.episodes(load());
//        assertEquals(1, episodes.size());
//        Episode e = episodes.get(0);
//        assertEquals(Resource.MEMORY, e.resource());
//        assertEquals(1791216436666L, e.startMs());
//        assertEquals(1791216472117L, e.endMs());
//        assertEquals(40517L, e.durationMs());
//    }

//    @Test
//    void aggregatesByName() throws Exception {
//        Recording rec = load();
//        List<Stats> all = ProcessAggregator.aggregate(rec.processes(), 0, Long.MAX_VALUE);
//        Stats idea = all.stream().filter(s -> s.name().equals("idea64")).findFirst().orElseThrow();
//        assertEquals(12.88, idea.cpuAvg(), 0.01);
//        assertEquals(3843, idea.rssAvgMb(), 1);
//    }

    @Test
    void reportMentionsTheEpisodeAndTheBiggestProcess() throws Exception {
        String text = ReportCommand.render(load(), new Lang(false), 10);
        assertTrue(text.contains("idea64"));
        assertTrue(text.contains("Memory"));
        assertTrue(text.contains("32 GB RAM"));
    }
}
