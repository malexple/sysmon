package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.Resource;
import ru.mcs.sysmon.analysis.Thresholds;
import ru.mcs.sysmon.cli.Lang;
import ru.mcs.sysmon.model.SystemSample;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Three lanes (CPU, memory, disk) over the whole recording. Bar height is utilization, bar colour is the
 * saturation level; red bands mark episodes, hatching marks gaps without data.
 * Drag to select an interval, click to clear it.
 */
final class TimelineStrips extends ChartBase {

    private record Lane(String label, Resource resource, ToDoubleFunction<SystemSample> value,
                        ToIntFunction<SystemSample> level, double threshold) {
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final BasicStroke DASH =
            new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f);

    private final ViewModel vm;
    private final List<Lane> lanes;
    private final double[] laneMax = new double[3];
    private final List<long[]> gaps = new ArrayList<>();
    private long[] ts = new long[0];
    private int cachedVersion = -1;

    private int left;
    private int plotW;
    private int top;
    private int laneH;
    private int gap;
    private int hoverX = -1;
    private int anchorX;

    TimelineStrips(ViewModel vm, Palette pal, Lang lang) {
        super(pal, lang);
        this.vm = vm;
        this.lanes = List.of(
                new Lane("CPU", Resource.CPU, SystemSample::cpuPct,
                        s -> Thresholds.cpu(s) ? 2 : (s.cpuPct() > 60 ? 1 : 0), Thresholds.CPU_PCT),
                new Lane(lang.t("Memory", "Память"), Resource.MEMORY, TimelineStrips::memUsed,
                        s -> Thresholds.memory(s) ? 2 : (memUsed(s) > 90 || s.pagesInPs() > 100 ? 1 : 0), Double.NaN),
                new Lane(lang.t("Disk", "Диск"), Resource.DISK, SystemSample::diskBusyPct,
                        s -> Thresholds.disk(s) ? 2 : (s.diskBusyPct() > 50 || s.diskQueue() >= 1 ? 1 : 0),
                        Thresholds.DISK_BUSY_PCT));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                computeLayout();
                anchorX = e.getX();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                hoverX = e.getX();
                if (Math.abs(e.getX() - anchorX) >= s(3)) {
                    vm.select(tOf(anchorX), tOf(e.getX()));
                } else {
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (Math.abs(e.getX() - anchorX) < s(3)) {
                    vm.clearSelection();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hoverX = e.getX();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverX = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        vm.addListener(this::repaint);
    }

    private static double memUsed(SystemSample s) {
        return s.memTotalMb() > 0 ? 100.0 - 100.0 * s.memAvailMb() / s.memTotalMb() : 0;
    }

    private void ensureCache() {
        if (cachedVersion == vm.version) {
            return;
        }
        cachedVersion = vm.version;
        List<SystemSample> sys = vm.recording.system();
        ts = sys.stream().mapToLong(SystemSample::tsMs).toArray();
        for (int i = 0; i < lanes.size(); i++) {
            double max = 0;
            for (SystemSample sm : sys) {
                max = Math.max(max, lanes.get(i).value().applyAsDouble(sm));
            }
            laneMax[i] = max;
        }
        gaps.clear();
        for (int i = 0; i + 1 < ts.length; i++) {
            if (ts[i + 1] - ts[i] > 3 * vm.interval) {
                gaps.add(new long[]{ts[i] + vm.interval, ts[i + 1]});
            }
        }
    }

    private void computeLayout() {
        left = s(112);
        int right = s(12);
        top = s(6);
        gap = s(6);
        int axisH = s(22);
        plotW = getWidth() - left - right;
        laneH = (getHeight() - top - axisH - 2 * gap) / 3;
    }

    private int xOf(long t) {
        TimeScale sc = vm.scale();
        double a = sc.map(vm.t0);
        double b = sc.map(vm.tEnd);
        double fr = (sc.map(t) - a) / Math.max(1e-9, b - a);
        fr = Math.max(0, Math.min(1, fr));
        return left + (int) Math.round(fr * plotW);
    }

    private long tOf(int x) {
        TimeScale sc = vm.scale();
        double a = sc.map(vm.t0);
        double b = sc.map(vm.tEnd);
        double fr = Math.max(0, Math.min(1, (x - left) / (double) Math.max(1, plotW)));
        return sc.unmap(a + fr * (b - a));
    }

    @Override
    protected void paintChart(Graphics2D g) {
        ensureCache();
        computeLayout();
        g.setColor(pal.chartBg);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (plotW < 20 || laneH < 8) {
            return;
        }
        List<SystemSample> sys = vm.recording.system();
        long span = Math.max(1, vm.tEnd - vm.t0);
        long[] ticks = TimeAxis.visibleTicks(vm.scale(), vm.t0, vm.tEnd, Math.max(2, plotW / s(90)));
        int lanesBottom = top + 3 * laneH + 2 * gap;
        int bodyH = laneH - 2;

        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            int y0 = top + i * (laneH + gap);
            g.setColor(pal.laneBg);
            g.fillRoundRect(left, y0, plotW, laneH, s(6), s(6));

            g.setColor(pal.grid);
            for (long t : ticks) {
                int x = xOf(t);
                g.drawLine(x, y0, x, y0 + laneH);
            }
            for (Episode e : vm.episodes) {
                if (e.resource() != lane.resource()) {
                    continue;
                }
                int x1 = xOf(e.startMs());
                int x2 = Math.max(x1 + 2, xOf(e.endMs() + vm.interval));
                g.setColor(Palette.alpha(pal.breach, 60));
                g.fillRect(x1, y0, x2 - x1, laneH);
            }

            double[] val = new double[plotW];
            int[] lvl = new int[plotW];
            Arrays.fill(lvl, -1);
            for (SystemSample sm : sys) {
                int a = xOf(sm.tsMs()) - left;
                int b = Math.max(a + 1, xOf(sm.tsMs() + vm.interval) - left) + 1;
                double v = lane.value().applyAsDouble(sm);
                int l = lane.level().applyAsInt(sm);
                for (int x = Math.max(0, a); x < Math.min(plotW, b); x++) {
                    val[x] = Math.max(val[x], v);
                    lvl[x] = Math.max(lvl[x], l);
                }
            }
            for (int x = 0; x < plotW; x++) {
                if (lvl[x] < 0) {
                    continue;
                }
                int h = (int) Math.round(Math.min(100.0, val[x]) / 100.0 * bodyH);
                if (h < 1 && val[x] > 0.05) {
                    h = 1;
                }
                g.setColor(pal.level(lvl[x]));
                g.fillRect(left + x, y0 + laneH - 1 - h, 1, h);
            }

            g.setColor(Palette.alpha(pal.textDim, 70));
            for (long[] gp : gaps) {
                int gx1 = xOf(gp[0]);
                int gx2 = xOf(gp[1]);
                if (gx2 - gx1 < 3) {
                    continue;
                }
                Shape old = g.getClip();
                g.clipRect(gx1, y0, gx2 - gx1, laneH);
                for (int hx = gx1 - laneH; hx < gx2; hx += s(8)) {
                    g.drawLine(hx, y0 + laneH, hx + laneH, y0);
                }
                g.setClip(old);
            }

            if (!Double.isNaN(lane.threshold())) {
                int ty = y0 + laneH - 1 - (int) Math.round(lane.threshold() / 100.0 * bodyH);
                g.setStroke(DASH);
                g.setColor(Palette.alpha(pal.textDim, 140));
                g.drawLine(left, ty, left + plotW, ty);
                g.setStroke(new BasicStroke(1f));
            }

            g.setFont(font(Font.BOLD, 1.0f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(pal.text);
            g.drawString(lane.label(), s(8), y0 + fm.getAscent() + s(4));
            g.setFont(font(Font.PLAIN, 0.85f));
            g.setColor(pal.textDim);
            g.drawString(f("max %.0f%%", laneMax[i]), s(8),
                    y0 + fm.getAscent() + s(4) + g.getFontMetrics().getHeight());
        }

        g.setFont(font(Font.PLAIN, 0.85f));
        FontMetrics fm = g.getFontMetrics();
        int axisY = lanesBottom + s(4);
        g.setColor(pal.textDim);
        int lastRight = Integer.MIN_VALUE;
        for (long t : ticks) {
            String label = TimeAxis.format(t, span);
            int w = fm.stringWidth(label);
            int x = xOf(t);
            if (x - w / 2 < lastRight + s(6)) {
                continue;
            }
            g.drawString(label, x - w / 2, axisY + fm.getAscent());
            lastRight = x + w / 2;
        }

        if (vm.hasSelection()) {
            int x1 = xOf(vm.from());
            int x2 = Math.max(x1 + 2, xOf(vm.to() + vm.interval));
            g.setColor(Palette.alpha(pal.accent, 50));
            g.fillRect(x1, top, x2 - x1, lanesBottom - top);
            g.setColor(Palette.alpha(pal.accent, 220));
            g.drawLine(x1, top, x1, lanesBottom);
            g.drawLine(x2, top, x2, lanesBottom);
        }
        if (hoverX >= left && hoverX <= left + plotW) {
            g.setColor(Palette.alpha(pal.text, 110));
            g.drawLine(hoverX, top, hoverX, lanesBottom);
        }
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        ensureCache();
        computeLayout();
        if (e.getX() < left || e.getX() > left + plotW || ts.length == 0) {
            return null;
        }
        long t = tOf(e.getX());
        int i = Arrays.binarySearch(ts, t);
        if (i < 0) {
            i = -i - 1;
        }
        i = Math.min(i, ts.length - 1);
        if (i > 0 && Math.abs(ts[i - 1] - t) < Math.abs(ts[i] - t)) {
            i--;
        }
        if (Math.abs(ts[i] - t) > vm.interval * 1.5) {
            return null;
        }
        SystemSample s = vm.recording.system().get(i);
        return "<html><b>" + TIME.format(Instant.ofEpochMilli(s.tsMs())) + "</b><br>"
                + f("CPU %.1f%%", s.cpuPct()) + "<br>"
                + f(lang.t("Memory used %.0f%% (free %d MB), page-ins %.0f/s",
                "Память занята %.0f%% (свободно %d МБ), page-in %.0f/с"), memUsed(s), s.memAvailMb(), s.pagesInPs()) + "<br>"
                + f(lang.t("Disk busy %.1f%%, queue %.1f, read %.2f MB/s, write %.2f MB/s",
                        "Диск занят %.1f%%, очередь %.1f, чтение %.2f МБ/с, запись %.2f МБ/с"),
                s.diskBusyPct(), s.diskQueue(), s.diskReadKbps() / 1024.0, s.diskWriteKbps() / 1024.0)
                + "</html>";
    }
}
