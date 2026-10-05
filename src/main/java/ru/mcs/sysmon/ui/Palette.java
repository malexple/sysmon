package ru.mcs.sysmon.ui;

import java.awt.Color;

/** Explicit chart colours, independent of the Look and Feel, so charts render the same everywhere. */
final class Palette {

    static final Palette DARK = new Palette(0x1E1F22, 0x26282C, 0x393B40, 0xBBBEC4, 0x7F848E,
            0x3E7CB1, 0xE5A93B, 0xE0524D, 0x4A9EE0);
    static final Palette LIGHT = new Palette(0xFFFFFF, 0xF3F5F8, 0xDADDE3, 0x2B2D30, 0x6C707E,
            0x5B9BD5, 0xE0A030, 0xD64541, 0x2F7DD1);

    final Color chartBg;
    final Color laneBg;
    final Color grid;
    final Color text;
    final Color textDim;
    final Color ok;
    final Color warn;
    final Color breach;
    final Color accent;
    final Color other = new Color(0x7F848E);
    final Color[] series = {
            new Color(0x5B9BD5), new Color(0x6CC08B), new Color(0xE5A93B), new Color(0xB07CD8), new Color(0x4FC1C6)
    };

    private Palette(int chartBg, int laneBg, int grid, int text, int textDim,
                    int ok, int warn, int breach, int accent) {
        this.chartBg = new Color(chartBg);
        this.laneBg = new Color(laneBg);
        this.grid = new Color(grid);
        this.text = new Color(text);
        this.textDim = new Color(textDim);
        this.ok = new Color(ok);
        this.warn = new Color(warn);
        this.breach = new Color(breach);
        this.accent = new Color(accent);
    }

    Color level(int level) {
        return level >= 2 ? breach : level == 1 ? warn : ok;
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    static Color blend(Color a, Color b, double t) {
        double k = Math.max(0, Math.min(1, t));
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }
}
