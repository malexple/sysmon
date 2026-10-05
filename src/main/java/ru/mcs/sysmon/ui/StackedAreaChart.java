package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.cli.Lang;
import ru.mcs.sysmon.model.ProcessSample;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Stacked area of the five biggest processes (by name) plus "other" over the selected interval,
 * for the chosen metric: CPU, memory or process I/O. Areas are broken at gaps without data.
 */
final class StackedAreaChart extends ChartBase {

    private static final int TOP = 5;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ViewModel vm;
    private Metric metric;

    private long[] ts = new long[0];
    private String[] names = new String[0];
    private double[][] vals = new double[0][0];
    private double[] avgs = new double[0];
    private double yMax = 1;

    private int left;
    private int top;
    private int plotW;
    private int plotH;
    private int hoverX = -1;

    StackedAreaChart(ViewModel vm, Palette pal, Lang lang, Metric metric) {
        super(pal, lang);
        this.vm = vm;
        this.metric = metric;
        prepare();
        vm.addListener(() -> {
            prepare();
            repaint();
        });
        MouseAdapter mouse = new MouseAdapter() {
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
        addMouseMotionListener(mouse);
        addMouseListener(mouse);
    }

    void setMetric(Metric m) {
        this.metric = m;
        prepare();
        repaint();
    }

    private double value(ProcessSample p) {
        return switch (metric) {
            case CPU -> p.cpuPct();
            case MEMORY -> p.rssMb();
            case IO -> p.readKbps() + p.writeKbps();
        };
    }

    private void prepare() {
        NavigableMap<Long, List<ProcessSample>> window =
                vm.recording.processes().subMap(vm.from(), true, vm.to(), true);
        int n = window.size();
        ts = new long[n];
        double[] total = new double[n];
        List<Map<String, Double>> rows = new ArrayList<>(n);
        Map<String, Double> sums = new HashMap<>();
        int i = 0;
        for (Map.Entry<Long, List<ProcessSample>> e : window.entrySet()) {
            ts[i] = e.getKey();
            Map<String, Double> perName = new HashMap<>();
            for (ProcessSample p : e.getValue()) {
                double v = value(p);
                total[i] += v;
                if (p.pid() >= 0) {
                    perName.merge(p.name(), v, Double::sum);
                }
            }
            rows.add(perName);
            for (Map.Entry<String, Double> me : perName.entrySet()) {
                sums.merge(me.getKey(), me.getValue(), Double::sum);
            }
            i++;
        }
        List<String> best = sums.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(TOP)
                .map(Map.Entry::getKey)
                .toList();
        int k = best.size();
        names = new String[k + 1];
        vals = new double[k + 1][n];
        avgs = new double[k + 1];
        for (int s = 0; s < k; s++) {
            names[s] = best.get(s);
        }
        names[k] = lang.t("other", "прочее");
        double max = 0;
        for (int t = 0; t < n; t++) {
            double used = 0;
            for (int s = 0; s < k; s++) {
                double v = rows.get(t).getOrDefault(names[s], 0.0);
                vals[s][t] = v;
                used += v;
            }
            vals[k][t] = Math.max(0, total[t] - used);
            max = Math.max(max, total[t]);
        }
        for (int s = 0; s <= k; s++) {
            avgs[s] = n == 0 ? 0 : Arrays.stream(vals[s]).average().orElse(0);
        }
        yMax = niceMax(max);
    }

    static double niceMax(double max) {
        if (max <= 0) {
            return 1;
        }
        double exp = Math.pow(10, Math.floor(Math.log10(max)) - 1);
        for (double m : new double[]{1, 2, 2.5, 5, 10, 20, 25, 50, 100}) {
            double s = m * exp;
            if (Math.ceil(max / s - 1e-9) <= 5) {
                return s;
            }
        }
        return 100 * exp;
    }

    private String title() {
        return switch (metric) {
            case CPU -> lang.t("CPU by process, % of machine", "CPU по процессам, % от машины");
            case MEMORY -> lang.t("Memory by process (RSS), MB", "Память по процессам (RSS), МБ");
            case IO -> lang.t("Process I/O (not only disk), KB/s", "Ввод-вывод процессов (не только диск), КБ/с");
        };
    }

    private String fmt(double v) {
        return switch (metric) {
            case CPU -> f("%.1f%%", v);
            case MEMORY -> v >= 1024 ? f("%.1f GB", v / 1024.0) : f("%.0f MB", v);
            case IO -> v >= 1024 ? f("%.1f MB/s", v / 1024.0) : f("%.0f KB/s", v);
        };
    }

    private Color colorOf(int k) {
        return k == names.length - 1 ? pal.other : pal.series[k % pal.series.length];
    }

    private double xOf(long t) {
        TimeScale sc = vm.scale();
        double a = sc.map(ts[0]);
        double b = sc.map(ts[ts.length - 1]);
        return left + (sc.map(t) - a) * plotW / Math.max(1e-9, b - a);
    }

    private double yOf(double v) {
        return top + plotH - v / yMax * plotH;
    }

    @Override
    protected void paintChart(Graphics2D g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(pal.chartBg);
        g.fillRect(0, 0, w, h);
        left = s(64);
        top = s(52);
        plotW = w - left - s(14);
        plotH = h - top - s(24);

        g.setFont(font(Font.BOLD, 1.0f));
        g.setColor(pal.text);
        g.drawString(title(), s(8), s(8) + g.getFontMetrics().getAscent());

        if (ts.length < 2 || plotW < 20 || plotH < 20) {
            g.setFont(font(Font.PLAIN, 1.0f));
            g.setColor(pal.textDim);
            g.drawString(lang.t("Not enough samples here - drag a wider interval on the strips.",
                    "Здесь мало замеров - выделите на полосах интервал шире."), s(8), top + Math.max(0, plotH) / 2);
            return;
        }

        g.setFont(font(Font.PLAIN, 0.85f));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            double v = yMax * i / 4.0;
            int y = (int) Math.round(yOf(v));
            g.setColor(pal.grid);
            g.drawLine(left, y, left + plotW, y);
            String label = fmt(v);
            g.setColor(pal.textDim);
            g.drawString(label, left - s(6) - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        long span = ts[ts.length - 1] - ts[0];
        int lastRight = Integer.MIN_VALUE;
        for (long t : TimeAxis.visibleTicks(vm.scale(), ts[0], ts[ts.length - 1], Math.max(2, plotW / s(90)))) {
            String label = TimeAxis.format(t, span);
            int lw = fm.stringWidth(label);
            int x = (int) Math.round(xOf(t));
            g.setColor(pal.grid);
            g.drawLine(x, top, x, top + plotH);
            if (x - lw / 2 < lastRight + s(6)) {
                continue;
            }
            g.setColor(pal.textDim);
            g.drawString(label, x - lw / 2, top + plotH + s(4) + fm.getAscent());
            lastRight = x + lw / 2;
        }

        int n = ts.length;
        double[] cum = new double[n];
        long maxStep = 3 * vm.interval;
        for (int k = 0; k < vals.length; k++) {
            Color c = colorOf(k);
            int segStart = 0;
            for (int t = 1; t <= n; t++) {
                if (t < n && ts[t] - ts[t - 1] <= maxStep) {
                    continue;
                }
                int segEnd = t - 1;
                if (segEnd > segStart) {
                    Path2D.Double area = new Path2D.Double();
                    Path2D.Double line = new Path2D.Double();
                    for (int i = segStart; i <= segEnd; i++) {
                        double x = xOf(ts[i]);
                        double y = yOf(cum[i] + vals[k][i]);
                        if (i == segStart) {
                            area.moveTo(x, y);
                            line.moveTo(x, y);
                        } else {
                            area.lineTo(x, y);
                            line.lineTo(x, y);
                        }
                    }
                    for (int i = segEnd; i >= segStart; i--) {
                        area.lineTo(xOf(ts[i]), yOf(cum[i]));
                    }
                    area.closePath();
                    g.setColor(Palette.alpha(c, 200));
                    g.fill(area);
                    g.setColor(c);
                    g.setStroke(new BasicStroke(1.2f));
                    g.draw(line);
                }
                segStart = t;
            }
            for (int i = 0; i < n; i++) {
                cum[i] += vals[k][i];
            }
        }
        g.setStroke(new BasicStroke(1f));

        int lx = left;
        int ly = s(38);
        for (int k = 0; k < names.length; k++) {
            String label = shorten(names[k]) + "  " + fmt(avgs[k]);
            int need = s(14) + fm.stringWidth(label) + s(16);
            if (lx + need > w - s(8)) {
                break;
            }
            g.setColor(colorOf(k));
            g.fillRoundRect(lx, ly - fm.getAscent() + s(2), s(10), s(10), s(3), s(3));
            g.setColor(pal.text);
            g.drawString(label, lx + s(14), ly);
            lx += need;
        }

        if (hoverX >= left && hoverX <= left + plotW) {
            g.setColor(Palette.alpha(pal.text, 110));
            g.drawLine(hoverX, top, hoverX, top + plotH);
        }
    }

    private static String shorten(String name) {
        return name.length() > 18 ? name.substring(0, 17) + "~" : name;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (ts.length < 2 || e.getX() < left || e.getX() > left + plotW) {
            return null;
        }
        TimeScale sc = vm.scale();
        double a = sc.map(ts[0]);
        double b = sc.map(ts[ts.length - 1]);
        double fr = (e.getX() - left) / (double) Math.max(1, plotW);
        long t = sc.unmap(a + fr * (b - a));
        int i = Arrays.binarySearch(ts, t);
        if (i < 0) {
            i = -i - 1;
        }
        i = Math.min(i, ts.length - 1);
        if (i > 0 && Math.abs(ts[i - 1] - t) < Math.abs(ts[i] - t)) {
            i--;
        }
        StringBuilder sb = new StringBuilder("<html><b>")
                .append(TIME.format(Instant.ofEpochMilli(ts[i]))).append("</b>");
        for (int k = vals.length - 1; k >= 0; k--) {
            sb.append("<br>").append(escape(names[k])).append(": ").append(fmt(vals[k][i]));
        }
        return sb.append("</html>").toString();
    }
}
