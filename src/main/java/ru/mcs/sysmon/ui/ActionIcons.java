package ru.mcs.sysmon.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Small action icons drawn with Java2D in the colour of the button text. */
final class ActionIcons {
    private ActionIcons() {}

    /** Two overlapping sheets. */
    static Icon copy(int size) {
        return new Glyph(size, false);
    }

    /** A check mark, shown for a moment after the copy. */
    static Icon check(int size) {
        return new Glyph(size, true);
    }

    private record Glyph(int size, boolean check) implements Icon {
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
                g2.setStroke(new BasicStroke(Math.max(1.3f, size / 11f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.translate(x, y);
                if (check) {
                    Path2D tick = new Path2D.Double();
                    tick.moveTo(size * 0.18, size * 0.52);
                    tick.lineTo(size * 0.40, size * 0.74);
                    tick.lineTo(size * 0.82, size * 0.28);
                    g2.draw(tick);
                } else {
                    // the front sheet, and only the visible edges of the sheet behind it
                    g2.draw(new RoundRectangle2D.Double(size * 0.12, size * 0.30, size * 0.50, size * 0.58,
                            size * 0.12, size * 0.12));
                    Path2D back = new Path2D.Double();
                    back.moveTo(size * 0.34, size * 0.30);
                    back.lineTo(size * 0.34, size * 0.12);
                    back.lineTo(size * 0.84, size * 0.12);
                    back.lineTo(size * 0.84, size * 0.70);
                    back.lineTo(size * 0.62, size * 0.70);
                    g2.draw(back);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}