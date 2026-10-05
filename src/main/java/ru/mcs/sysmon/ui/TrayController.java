package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.cli.Lang;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** The notification-area icon: shows that recording runs and offers stop / open window / exit. */
final class TrayController {

    private final Runnable openWindow;
    private final Runnable stopRecording;
    private final Runnable stopAndOpenFolder;
    private final Runnable exit;
    private Lang lang;
    private TrayIcon icon;
    private boolean recording;
    private String tooltip = "sysmon";

    TrayController(Lang lang, Runnable openWindow, Runnable stopRecording, Runnable stopAndOpenFolder, Runnable exit) {
        this.lang = lang;
        this.openWindow = openWindow;
        this.stopRecording = stopRecording;
        this.stopAndOpenFolder = stopAndOpenFolder;
        this.exit = exit;
    }

    /** Returns false when the system has no tray; the window then stays the only interface. */
    boolean install() {
        if (!SystemTray.isSupported()) {
            return false;
        }
        try {
            Dimension size = SystemTray.getSystemTray().getTrayIconSize();
            icon = new TrayIcon(icon(Math.max(16, size.width), false), tooltip, menu());
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openWindow.run());
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (AWTException | RuntimeException e) {
            icon = null;
            return false;
        }
    }

    void setLang(Lang value) {
        lang = value;
        if (icon != null) {
            icon.setPopupMenu(menu());
        }
    }

    void setRecording(boolean value, String tip) {
        String text = tip == null || tip.isEmpty() ? "sysmon" : tip;
        if (icon == null) {
            recording = value;
            tooltip = text;
            return;
        }
        if (value != recording) {
            recording = value;
            Dimension size = SystemTray.getSystemTray().getTrayIconSize();
            icon.setImage(icon(Math.max(16, size.width), value));
            icon.setPopupMenu(menu());
        }
        if (!text.equals(tooltip)) {
            tooltip = text;
            icon.setToolTip(text);
        }
    }

    void popup(String title, String message) {
        if (icon != null) {
            icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    void remove() {
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
        }
    }

    private PopupMenu menu() {
        PopupMenu menu = new PopupMenu();
        menu.add(item(lang.t("Open window", "Открыть окно"), openWindow, true));
        menu.addSeparator();
        menu.add(item(lang.t("Stop recording", "Остановить запись"), stopRecording, recording));
        menu.add(item(lang.t("Stop and open folder", "Остановить и открыть папку"), stopAndOpenFolder, recording));
        menu.addSeparator();
        menu.add(item(lang.t("Exit", "Выход"), exit, true));
        return menu;
    }

    private static MenuItem item(String label, Runnable action, boolean enabled) {
        MenuItem item = new MenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** A small bar-chart glyph, with a red dot while recording. Also used as the window icon. */
    static Image icon(int size, boolean recording) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x2B2D30));
            g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
            int bar = Math.max(2, size / 6);
            int gapPx = Math.max(1, size / 10);
            int x = size / 6;
            int[] heights = {size * 5 / 10, size * 7 / 10, size * 4 / 10};
            Color[] colors = {new Color(0x5B9BD5), new Color(0x6CC08B), new Color(0xE5A93B)};
            for (int i = 0; i < 3; i++) {
                g.setColor(colors[i]);
                g.fillRect(x, size - size / 6 - heights[i], bar, heights[i]);
                x += bar + gapPx;
            }
            if (recording) {
                int d = Math.max(5, size * 4 / 10);
                g.setColor(Color.WHITE);
                g.fillOval(size - d - 1, 0, d + 1, d + 1);
                g.setColor(new Color(0xE0524D));
                g.fillOval(size - d, 1, d - 1, d - 1);
            }
        } finally {
            g.dispose();
        }
        return img;
    }
}
