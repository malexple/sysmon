package ru.mcs.sysmon.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/**
 * Sun and moon for the theme switch, drawn with Java2D in the colour of the button text,
 * so they look the same on every system and in both themes (no emoji fonts needed).
 */
final class ThemeIcons {
    private ThemeIcons() {}

    static Icon sun(int size) {
        return new GlyphIcon(size, true);
    }

    static Icon moon(int size) {
        return new GlyphIcon(size, false);
    }

    private record GlyphIcon(int size, boolean sun) implements Icon {
        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.setColor(c.getForeground());
                g2.translate(x, y);
                if (sun) {
                    paintSun(g2);
                } else {
                    paintMoon(g2);
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintSun(Graphics2D g2) {
            double mid = size / 2.0;
            double r = size * 0.2;
            g2.fill(new Ellipse2D.Double(mid - r, mid - r, 2 * r, 2 * r));
            g2.setStroke(new BasicStroke(Math.max(1.2f, size / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int k = 0; k < 8; k++) {
                double a = Math.PI / 4 * k;
                double cos = Math.cos(a);
                double sin = Math.sin(a);
                g2.draw(new Line2D.Double(mid + cos * size * 0.34, mid + sin * size * 0.34,
                        mid + cos * size * 0.48, mid + sin * size * 0.48));
            }
        }

        private void paintMoon(Graphics2D g2) {
            Area moon = new Area(new Ellipse2D.Double(size * 0.12, size * 0.12, size * 0.76, size * 0.76));
            moon.subtract(new Area(new Ellipse2D.Double(size * 0.38, size * 0.02, size * 0.7, size * 0.7)));
            g2.fill(moon);
        }
    }
}
