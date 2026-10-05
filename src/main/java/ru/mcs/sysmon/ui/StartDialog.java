package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.cli.Lang;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.time.Duration;

/** The "Start recording" dialog: how long, how often, where to. */
final class StartDialog {

    record Result(Path dir, int intervalSec, Duration duration) {
    }

    private StartDialog() {
    }

    /** Returns the chosen settings or null if the user cancelled. */
    static Result show(Component parent, Lang lang, Path defaultDir) {
        JComboBox<String> durationBox = new JComboBox<>(new String[]{
                "1 " + lang.t("h", "ч"), "4 " + lang.t("h", "ч"), "8 " + lang.t("h", "ч"), "12 " + lang.t("h", "ч"),
                lang.t("Unlimited", "Без ограничения")});
        durationBox.setSelectedIndex(2);
        JSpinner interval = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
        JTextField dirField = new JTextField(defaultDir.toAbsolutePath().toString(), 32);
        JButton browse = new JButton("...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(defaultDir.toAbsolutePath().toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel panel = new JPanel(new GridBagLayout());
        row(panel, 0, lang.t("Duration", "Длительность"), durationBox, null);
        row(panel, 1, lang.t("Interval, s", "Интервал, с"), interval, null);
        row(panel, 2, lang.t("Folder", "Папка"), dirField, browse);
        GridBagConstraints note = new GridBagConstraints();
        note.gridx = 0;
        note.gridy = 3;
        note.gridwidth = 3;
        note.anchor = GridBagConstraints.WEST;
        note.insets = new Insets(10, 4, 0, 4);
        panel.add(new JLabel(lang.t("Only process names are recorded (no paths, no arguments).",
                "Записываются только имена процессов (без путей и аргументов).")), note);

        int answer = JOptionPane.showConfirmDialog(parent, panel, lang.t("Start recording", "Начать запись"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        Duration duration = switch (durationBox.getSelectedIndex()) {
            case 0 -> Duration.ofHours(1);
            case 1 -> Duration.ofHours(4);
            case 2 -> Duration.ofHours(8);
            case 3 -> Duration.ofHours(12);
            default -> null;
        };
        String text = dirField.getText().trim();
        Path dir = text.isEmpty() ? defaultDir : Path.of(text);
        return new Result(dir, (Integer) interval.getValue(), duration);
    }

    private static void row(JPanel panel, int y, String label, Component field, Component extra) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
        if (extra != null) {
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(extra, c);
        }
    }
}
