package ru.mcs.sysmon.ui;

import org.junit.jupiter.api.Test;
import ru.mcs.sysmon.analysis.Episodes;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.cli.Lang;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless checks on the real 2-minute recording; the window itself is checked by eye. */
class UiTest {

    private static final long EP_FROM = 1791216436666L;
    private static final long EP_TO = 1791216472117L;

    private ViewModel model() throws Exception {
        Recording rec = Recording.load(Path.of(getClass().getResource("/sample").toURI()));
        return new ViewModel(rec, Episodes.detectAll(rec.system()));
    }

    private static int distinctColors(ChartBase chart, int w, int h) {
        chart.setSize(w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        chart.paint(g);
        g.dispose();
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                colors.add(img.getRGB(x, y));
            }
        }
        return colors.size();
    }

    @Test
    void timelineAndChartDrawSomething() throws Exception {
        ViewModel vm = model();
        assertTrue(distinctColors(new TimelineStrips(vm, Palette.DARK, new Lang(false)), 1000, 220) > 8);
        assertTrue(distinctColors(new StackedAreaChart(vm, Palette.DARK, new Lang(false), Metric.CPU), 1000, 320) > 8);
        assertTrue(distinctColors(new StackedAreaChart(vm, Palette.LIGHT, new Lang(true), Metric.MEMORY), 1000, 320) > 8);
    }

    @Test
    void chartFollowsTheSelection() throws Exception {
        ViewModel vm = model();
        StackedAreaChart chart = new StackedAreaChart(vm, Palette.DARK, new Lang(false), Metric.IO);
        vm.select(EP_FROM, EP_TO);
        assertTrue(distinctColors(chart, 1000, 320) > 8);
        vm.clearSelection();
        assertEquals(vm.t0, vm.from());
    }

    @Test
    void verdictNamesTheEpisodeAndTheCulprits() throws Exception {
        ViewModel vm = model();
        String whole = Verdict.text(vm.recording, vm.episodes, vm.from(), vm.to(), true, new Lang(false));
        assertTrue(whole.contains("Saturation episodes: 1"), whole);
        assertTrue(whole.startsWith("Samples: 24"), whole);

        String window = Verdict.text(vm.recording, vm.episodes, EP_FROM, EP_TO, false, new Lang(false));
        assertTrue(window.contains("2566"), window);
        assertTrue(window.contains("idea64"), window);
        assertTrue(window.contains("java"), window);
        assertTrue(window.contains("Memory"), window);
    }

    @Test
    void niceStepEndsTheAxisJustAboveTheData() {
        assertEquals(10, StackedAreaChart.niceStep(44.72), 1e-9);
        assertEquals(20, StackedAreaChart.niceStep(50.4), 1e-9);
        assertEquals(10000, StackedAreaChart.niceStep(26500), 1e-9);
        assertEquals(1, StackedAreaChart.niceStep(0), 1e-9);
    }
}
