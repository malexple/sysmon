package ru.mcs.sysmon.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.Episodes;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.model.SystemSample;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export on the real 2-minute recording: the one memory episode is 1791216436666..1791216472117. */
class ExporterTest {
    private static final Lang EN = new Lang(false);

    @TempDir
    Path tmp;

    private Recording load() throws Exception {
        return Recording.load(Path.of(getClass().getResource("/sample").toURI()));
    }

    private static List<String> unzip(Path zip, Path target) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
                Path file = target.resolve(e.getName());
                Files.createDirectories(file.getParent());
                Files.write(file, readAll(in));
            }
        }
        return names;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    @Test
    void episodeFolderHoldsExactlyTheWindowAndOpensAgain() throws Exception {
        Recording rec = load();
        List<Episode> all = Exporter.numbered(Episodes.detectAll(rec.system()));
        Episode e = all.get(0);
        Path zip = tmp.resolve("out.zip");

        int folders = Exporter.write(rec, Exporter.piecesFor(all, all), 0, EN, zip);

        assertEquals(1, folders);
        Path unpacked = tmp.resolve("unpacked");
        List<String> names = unzip(zip, unpacked);
        assertTrue(names.contains("summary.txt"));
        assertTrue(names.contains("episodes.csv"));
        assertTrue(names.stream().anyMatch(n -> n.startsWith("episode-1-memory-") && n.endsWith("/system-export.csv")));

        Recording back = Recording.load(unpacked);
        long expected = rec.system().stream()
                .filter(s -> s.tsMs() >= e.startMs() && s.tsMs() <= e.endMs()).count();
        assertTrue(expected > 1);
        assertEquals(expected, back.system().size());
        assertTrue(back.system().stream().allMatch(s -> s.tsMs() >= e.startMs() && s.tsMs() <= e.endMs()));
        assertEquals(expected, back.processes().size());
        assertEquals(0, back.skippedLines());
    }

    @Test
    void paddingAddsContextOnBothSides() throws Exception {
        Recording rec = load();
        List<Episode> all = Exporter.numbered(Episodes.detectAll(rec.system()));
        Episode e = all.get(0);
        long pad = 10_000;
        Path zip = tmp.resolve("pad.zip");

        Exporter.write(rec, Exporter.piecesFor(all, all), pad, EN, zip);
        unzip(zip, tmp.resolve("unpacked"));

        long expected = rec.system().stream()
                .filter(s -> s.tsMs() >= e.startMs() - pad && s.tsMs() <= e.endMs() + pad).count();
        assertEquals(expected, Recording.load(tmp.resolve("unpacked")).system().size());
    }

    @Test
    void customIntervalGetsItsOwnFolder() throws Exception {
        Recording rec = load();
        List<SystemSample> s = rec.system();
        long from = s.get(2).tsMs();
        long to = s.get(6).tsMs();
        Path zip = tmp.resolve("custom.zip");

        Exporter.write(rec, List.of(Exporter.intervalPiece(from, to)), 0, EN, zip);

        List<String> names = unzip(zip, tmp.resolve("unpacked"));
        assertTrue(names.stream().anyMatch(n -> n.startsWith("custom-interval-")));
        assertEquals(5, Recording.load(tmp.resolve("unpacked")).system().size());
    }

    @Test
    void chooseFollowsTheCascade() throws Exception {
        Recording rec = load();
        List<Episode> all = Exporter.numbered(Episodes.detectAll(rec.system()));

        assertEquals("memory", Exporter.choose(all, all, true, 1, 2).get(0).kind());
        assertEquals("custom", Exporter.choose(all, List.of(), true, 1, 2).get(0).kind());
        assertEquals("memory", Exporter.choose(all, List.of(), false, 0, 0).get(0).kind());
        assertEquals(all.size(), Exporter.choose(all, List.of(), false, 0, 0).size());
    }

    @Test
    void unknownEpisodeNumberIsRejected() throws Exception {
        Recording rec = load();
        List<Episode> all = Exporter.numbered(Episodes.detectAll(rec.system()));
        assertThrows(IllegalArgumentException.class, () -> Exporter.piecesByNumber(all, Set.of(5)));
        assertEquals(1, Exporter.piecesByNumber(all, Set.of(1)).size());
    }

    @Test
    void emptyRangeThrowsAndLeavesNoFile() throws Exception {
        Recording rec = load();
        Path zip = tmp.resolve("none.zip");
        assertThrows(IllegalStateException.class,
                () -> Exporter.write(rec, List.of(Exporter.intervalPiece(0, 1)), 0, EN, zip));
        assertFalse(Files.exists(zip));
    }
}
