package ru.mcs.sysmon.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.Episodes;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.analysis.Resource;
import ru.mcs.sysmon.cli.Lang;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** The main window: toolbar, verdict line, saturation strips, episode list, stacked chart and process table. */
public final class SysmonWindow {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame();
    private final javax.swing.Timer liveTimer = new javax.swing.Timer(15_000, e -> reload());
    private Path dir;
    private Lang lang;
    private boolean dark;
    private boolean live;
    private boolean loading;
    private Metric metric = Metric.CPU;
    private ViewModel vm;
    private StackedAreaChart chart;

    private SysmonWindow(Path dir, Lang lang, boolean dark) {
        this.dir = dir;
        this.lang = lang;
        this.dark = dark;
    }

    public static void launch(Path dir, Lang lang, boolean dark) {
        SwingUtilities.invokeLater(() -> {
            applyLaf(dark);
            new SysmonWindow(dir, lang, dark).start();
        });
    }

    private static void applyLaf(boolean dark) {
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    private void start() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1320, 860);
        frame.setLocationRelativeTo(null);
        frame.setTitle("sysmon - " + dir.toAbsolutePath());
        frame.setVisible(true);
        reload();
    }

    private void reload() {
        if (loading) {
            return;
        }
        loading = true;
        if (vm == null) {
            showMessage(lang.t("Loading...", "Загрузка..."));
        }
        final Path source = dir;
        new SwingWorker<Recording, Void>() {
            @Override
            protected Recording doInBackground() throws Exception {
                return Recording.load(source);
            }

            @Override
            protected void done() {
                loading = false;
                Recording rec;
                try {
                    rec = get();
                } catch (Exception e) {
                    if (vm == null) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        showMessage(lang.t("Cannot load ", "Не удалось загрузить ") + source + ": " + cause);
                    }
                    return;
                }
                try {
                    install(rec);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    showMessage(lang.t("Cannot show: ", "Не удалось показать: ") + e);
                }
            }
        }.execute();
    }

    private void install(Recording rec) {
        if (rec.system().isEmpty()) {
            vm = null;
            showMessage(lang.t("No samples in ", "Нет замеров в ") + dir.toAbsolutePath());
            return;
        }
        if (vm != null && vm.recording.system().size() == rec.system().size()) {
            return;
        }
        boolean keep = vm != null && vm.hasSelection();
        long keepFrom = keep ? vm.from() : 0;
        long keepTo = keep ? vm.to() : 0;
        ViewModel next = new ViewModel(rec, Episodes.detectAll(rec.system()));
        if (keep && keepFrom >= next.t0 && keepTo <= next.t1) {
            next.select(keepFrom, keepTo);
        }
        vm = next;
        buildContent();
    }

    private void showMessage(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        frame.setContentPane(label);
        frame.revalidate();
        frame.repaint();
    }

    private void buildContent() {
        vm.clearListeners();
        Palette pal = dark ? Palette.DARK : Palette.LIGHT;

        TimelineStrips strips = new TimelineStrips(vm, pal, lang);
        strips.setPreferredSize(new Dimension(800, 210));
        chart = new StackedAreaChart(vm, pal, lang, metric);
        ProcessTablePanel table = new ProcessTablePanel(vm, pal, lang);

        chart.setPreferredSize(new Dimension(800, 330));
        chart.setMinimumSize(new Dimension(200, 160));
        table.setPreferredSize(new Dimension(800, 260));

        JTextArea verdict = new JTextArea(3, 10);
        verdict.setEditable(false);
        verdict.setLineWrap(true);
        verdict.setWrapStyleWord(true);
        verdict.setOpaque(false);
        verdict.setFont(UIManager.getFont("Label.font"));
        verdict.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        Runnable updateVerdict = () -> verdict.setText(
                Verdict.text(vm.recording, vm.episodes, vm.from(), vm.to(), !vm.hasSelection(), lang));
        vm.addListener(updateVerdict);
        updateVerdict.run();

        JPanel north = new JPanel(new BorderLayout());
        north.add(verdict, BorderLayout.CENTER);
        north.add(strips, BorderLayout.SOUTH);

        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, table);
        lower.setResizeWeight(0.6);
        lower.setBorder(null);

        JPanel right = new JPanel(new BorderLayout());
        right.add(north, BorderLayout.NORTH);
        right.add(lower, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, episodePanel(), right);
        main.setDividerLocation(220);
        main.setBorder(null);

        JPanel root = new JPanel(new BorderLayout());
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(main, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setTitle("sysmon - " + dir.toAbsolutePath());
        frame.revalidate();
        frame.repaint();
    }

    private JPanel episodePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel(lang.t("Saturation episodes", "Эпизоды насыщения") + " (" + vm.episodes.size() + ")");
        title.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        panel.add(title, BorderLayout.NORTH);
        if (vm.episodes.isEmpty()) {
            panel.add(new JLabel(lang.t("none found", "не найдено"), SwingConstants.CENTER), BorderLayout.CENTER);
            return panel;
        }
        DefaultListModel<Episode> model = new DefaultListModel<>();
        vm.episodes.forEach(model::addElement);
        JList<Episode> list = new JList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                Episode e = (Episode) value;
                setText(TIME.format(Instant.ofEpochMilli(e.startMs())) + " - " + TIME.format(Instant.ofEpochMilli(e.endMs()))
                        + "  " + resourceName(e.resource()) + " (" + e.durationMs() / 1000 + " " + lang.t("s", "с") + ")");
                return this;
            }
        });
        list.addListSelectionListener(ev -> {
            Episode e = list.getSelectedValue();
            if (!ev.getValueIsAdjusting() && e != null) {
                vm.select(e.startMs() - vm.interval, e.endMs() + vm.interval);
            }
        });
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        return panel;
    }

    private String resourceName(Resource r) {
        return switch (r) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case DISK -> lang.t("Disk", "Диск");
        };
    }

    private JToolBar toolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        JButton open = new JButton(lang.t("Open folder...", "Открыть папку..."));
        open.addActionListener(e -> chooseDir());
        JButton refresh = new JButton(lang.t("Reload", "Обновить"));
        refresh.addActionListener(e -> reload());
        JCheckBox liveBox = new JCheckBox(lang.t("Live (15 s)", "Живой режим (15 с)"), live);
        liveBox.addActionListener(e -> setLive(liveBox.isSelected()));
        tb.add(open);
        tb.add(refresh);
        tb.add(liveBox);
        tb.addSeparator();

        tb.add(new JLabel(lang.t("Chart: ", "График: ")));
        ButtonGroup group = new ButtonGroup();
        for (Metric m : Metric.values()) {
            JToggleButton button = new JToggleButton(metricName(m), m == metric);
            button.addActionListener(e -> {
                metric = m;
                chart.setMetric(m);
            });
            group.add(button);
            tb.add(button);
        }

        tb.add(Box.createHorizontalGlue());
        JButton theme = new JButton(dark ? lang.t("Light theme", "Светлая тема") : lang.t("Dark theme", "Тёмная тема"));
        theme.addActionListener(e -> toggleTheme());
        JButton language = new JButton(lang.ru() ? "EN" : "RU");
        language.addActionListener(e -> {
            lang = new Lang(!lang.ru());
            buildContent();
        });
        tb.add(theme);
        tb.add(language);
        return tb;
    }

    private String metricName(Metric m) {
        return switch (m) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case IO -> lang.t("I/O", "Ввод-вывод");
        };
    }

    private void toggleTheme() {
        dark = !dark;
        applyLaf(dark);
        FlatLaf.updateUI();
        buildContent();
    }

    private void setLive(boolean value) {
        live = value;
        if (live) {
            liveTimer.start();
        } else {
            liveTimer.stop();
        }
    }

    private void chooseDir() {
        JFileChooser chooser = new JFileChooser(dir.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            dir = chooser.getSelectedFile().toPath();
            vm = null;
            reload();
        }
    }
}
