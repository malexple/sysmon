package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.cli.Lang;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/** Common base for the custom-painted charts: antialiasing, UI scale taken from the Look and Feel font. */
abstract class ChartBase extends JComponent {

    protected final Palette pal;
    protected final Lang lang;
    private final float unit;

    ChartBase(Palette pal, Lang lang) {
        this.pal = pal;
        this.lang = lang;
        this.unit = baseFont().getSize2D() / 12f;
        ToolTipManager.sharedInstance().registerComponent(this);
        setOpaque(true);
    }

    static Font baseFont() {
        Font f = null;
        try {
            f = UIManager.getFont("Label.font");
        } catch (RuntimeException ignored) {
            // fall through to the default below
        }
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    /** Scales a pixel size with the UI font so charts follow HiDPI settings. */
    protected int s(int px) {
        return Math.max(1, Math.round(px * unit));
    }

    protected Font font(int style, float factor) {
        Font b = baseFont();
        return b.deriveFont(style, b.getSize2D() * factor);
    }

    @Override
    protected final void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintChart(g2);
        } finally {
            g2.dispose();
        }
    }

    protected abstract void paintChart(Graphics2D g);

    protected static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }

    protected static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
