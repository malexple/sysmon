package ru.mcs.sysmon.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.Episodes;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.analysis.Resource;
import ru.mcs.sysmon.cli.Lang;
import ru.mcs.sysmon.recording.RecordingSession;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import ru.mcs.sysmon.cli.Exporter;

import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The main window: recording controls, strips, episode list, stacked chart and process table.
 * It can record by itself (a background thread of this process) and then lives in the tray.
 */
public final class SysmonWindow {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame();
    private final javax.swing.Timer liveTimer = new javax.swing.Timer(15_000, e -> reload());
    private final javax.swing.Timer statusTimer = new javax.swing.Timer(1000, e -> updateRecordingUi());
    private final DefaultListModel<Episode> episodeModel = new DefaultListModel<>();
    private final JLabel episodeTitle = new JLabel();

    private Path dir;
    private Lang lang;
    private boolean dark;
    private boolean live;
    private boolean compress;
    private boolean loading;
    private boolean viewOnly;
    private int episodeVersion = -1;
    private String lastMessage = "";
    private Metric metric = Metric.CPU;
    private ViewModel vm;
    private StackedAreaChart chart;
    private RecordingSession session;
    private TrayController tray;
    private JLabel recLabel;
    private JButton startButton;
    private JButton stopButton;
    private JCheckBox liveBox;
    private JList<Episode> episodeList;
    private boolean listDriving;
    private boolean lastHas;
    private long lastFrom;
    private long lastTo;
    private JButton exportButton;

    private SysmonWindow(Path dir, Lang lang, boolean dark) {
        this.dir = dir;
        this.lang = lang;
        this.dark = dark;
    }

    public static void launch(Path dir, Lang lang, boolean dark, boolean offerRecording) {
        SwingUtilities.invokeLater(() -> {
            applyLaf(dark);
            new SysmonWindow(dir, lang, dark).start(offerRecording);
        });
    }

    private static void applyLaf(boolean dark) {
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    private void start(boolean offerRecording) {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onCloseRequested();
            }
        });
        frame.setSize(1320, 860);
        frame.setMinimumSize(new Dimension(1100, 600));
        frame.setLocationRelativeTo(null);
        frame.setIconImage(TrayController.icon(32, false));
        frame.setTitle(title());
        tray = new TrayController(lang, this::showWindow, this::requestStop, this::stopAndOpenFolder, this::exit);
        if (!tray.install()) {
            tray = null;
        }
        statusTimer.start();
        frame.setVisible(true);
        reload();
        if (offerRecording) {
            SwingUtilities.invokeLater(this::showStartDialog);
        }
    }

    private String title() {
        return "sysmon - " + dir.toAbsolutePath();
    }

    // ---- loading ------------------------------------------------------------------------------

    private void reload() {
        if (loading) {
            return;
        }
        loading = true;
        if (vm == null && lastMessage.isEmpty()) {
            showMessage(lang.t("Loading...", "Загрузка..."));
        }
        final Path source = dir;
        new SwingWorker<Recording, Void>() {
            @Override
            protected Recording doInBackground() throws Exception {
                if (!Files.isDirectory(source)) {
                    return new Recording(new ArrayList<>(), new TreeMap<>(), 0);
                }
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
                if (!source.equals(dir)) {
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
        refreshViewOnly();
        if (rec.system().isEmpty()) {
            vm = null;
            showMessage(isRecording()
                    ? lang.t("Recording is running, the first samples will appear in a moment...",
                    "Запись идёт, первые данные появятся через несколько секунд...")
                    : lang.t("No data yet. Press \"Start recording\".", "Данных пока нет. Нажмите «Начать запись»."));
            return;
        }
        List<Episode> episodes = Episodes.detectAll(rec.system());
        if (vm == null) {
            vm = new ViewModel(rec, episodes);
            vm.setCompressGaps(compress);
            buildContent();
        } else if (vm.recording.system().size() != rec.system().size()) {
            vm.update(rec, episodes);
        }
        updateRecordingUi();
    }

    // ---- content ------------------------------------------------------------------------------

    private void showMessage(String text) {
        lastMessage = text;
        JPanel root = new JPanel(new BorderLayout());
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(new JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.revalidate();
        frame.repaint();
        updateRecordingUi();
    }

    private void buildContent() {
        lastMessage = "";
        vm.clearListeners();
        vm.setCompressGaps(compress);
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

        episodeVersion = -1;
        syncEpisodes();
        vm.addListener(this::syncEpisodes);
        vm.addListener(this::followStrips);

        JPanel north = new JPanel(new BorderLayout());
        north.add(verdictPanel(verdict), BorderLayout.CENTER);
        north.add(strips, BorderLayout.SOUTH);

        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartBox(pal), table);
        lower.setResizeWeight(0.6);
        lower.setBorder(null);

        JPanel right = new JPanel(new BorderLayout());
        right.add(north, BorderLayout.NORTH);
        right.add(lower, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, episodePanel(), right);
        main.setDividerLocation(220);
        main.setBorder(null);

        // A small margin so that nothing touches the edge of the window.
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        body.add(main, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setTitle(title());
        frame.revalidate();
        frame.repaint();
        updateRecordingUi();
    }

    /** A list highlight must always mean "this is the selected interval", so dragging on the strips clears it. */
    private void followStrips() {
        boolean has = vm.hasSelection();
        long from = vm.from();
        long to = vm.to();
        boolean changed = has != lastHas || from != lastFrom || to != lastTo;
        lastHas = has;
        lastFrom = from;
        lastTo = to;
        if (changed && !listDriving && episodeList != null) {
            episodeList.clearSelection();
        }
    }

    private void syncEpisodes() {
        if (vm == null || episodeVersion == vm.version) {
            return;
        }
        episodeVersion = vm.version;
        episodeModel.clear();
        vm.episodes.forEach(episodeModel::addElement);
        episodeTitle.setText(lang.t("Saturation episodes", "Эпизоды насыщения") + " (" + vm.episodes.size() + ")");
    }

    private JPanel episodePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        episodeTitle.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        panel.add(episodeTitle, BorderLayout.NORTH);
        episodeList = new JList<>(episodeModel);
        episodeList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        episodeList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                Episode e = (Episode) value;
                setText(TIME.format(Instant.ofEpochMilli(e.startMs())) + " - "
                        + TIME.format(Instant.ofEpochMilli(e.endMs())) + "  "
                        + resourceName(e.resource()) + " (" + e.durationMs() / 1000 + " " + lang.t("s", "с") + ")");
                return this;
            }
        });
        episodeList.addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting() || vm == null || episodeList.getSelectedIndices().length != 1) {
                return; // several episodes are only picked for export, the interval stays as it is
            }
            Episode e = episodeList.getSelectedValue();
            listDriving = true;
            try {
                vm.select(e.startMs() - vm.interval, e.endMs() + vm.interval);
            } finally {
                listDriving = false;
            }
        });
        panel.add(new JScrollPane(episodeList), BorderLayout.CENTER);
        return panel;
    }

    private String resourceName(Resource r) {
        return switch (r) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case DISK -> lang.t("Disk", "Диск");
        };
    }

    private JPanel toolbar() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        // 1. Recording: only the dot is red, the button itself stays calm.
        startButton = new JButton("<html><font color='#E0524D'>\u25CF</font> "
                + lang.t("Start recording", "Начать запись") + "</html>");
        startButton.addActionListener(e -> showStartDialog());
        stopButton = new JButton(lang.t("\u25A0 Stop", "\u25A0 Остановить"));
        stopButton.addActionListener(e -> requestStop());
        recLabel = new JLabel();
        recLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        left.add(startButton);
        left.add(stopButton);
        left.add(recLabel);
        left.add(separator());

        // 2. Data source.
        JButton open = new JButton(lang.t("Open folder...", "Открыть папку..."));
        open.addActionListener(e -> chooseDir());
        JButton refresh = new JButton(lang.t("Reload", "Обновить"));
        refresh.addActionListener(e -> reload());
        liveBox = new JCheckBox(lang.t("Live (15 s)", "Живой режим (15 с)"), live);
        liveBox.addActionListener(e -> setLive(liveBox.isSelected()));
        left.add(open);
        left.add(refresh);
        left.add(liveBox);
        left.add(separator());

        // 3. View: affects the strips and the chart alike.
        JCheckBox compressBox = new JCheckBox(lang.t("Compact gaps", "Сжать пропуски"), compress);
        compressBox.addActionListener(e -> {
            compress = compressBox.isSelected();
            if (vm != null) {
                vm.setCompressGaps(compress);
            }
        });
        left.add(compressBox);

        // 4. The final action and the look of the window.
        exportButton = new JButton(lang.t("Export to ZIP...", "Экспорт в ZIP..."));
        exportButton.setToolTipText(lang.t(
                "Save to a zip: the episodes picked in the list, else the interval dragged on the strips, else all episodes",
                "Сохранить в zip: эпизоды, выбранные в списке, иначе интервал с полос, иначе все эпизоды"));
        exportButton.setEnabled(vm != null);
        exportButton.addActionListener(e -> exportZip());
        right.add(exportButton);
        right.add(separator());

        int iconSize = Math.round(ChartBase.baseFont().getSize2D() * 1.3f);
        JToggleButton light = new JToggleButton(ThemeIcons.sun(iconSize), !dark);
        light.setToolTipText(lang.t("Light theme", "Светлая тема"));
        light.addActionListener(e -> setDark(false));
        JToggleButton night = new JToggleButton(ThemeIcons.moon(iconSize), dark);
        night.setToolTipText(lang.t("Dark theme", "Тёмная тема"));
        night.addActionListener(e -> setDark(true));
        ButtonGroup themeGroup = new ButtonGroup();
        themeGroup.add(light);
        themeGroup.add(night);
        right.add(segmented(light, night));

        JToggleButton ru = new JToggleButton("RU", lang.ru());
        ru.addActionListener(e -> setLanguage(true));
        JToggleButton en = new JToggleButton("EN", !lang.ru());
        en.addActionListener(e -> setLanguage(false));
        ButtonGroup languageGroup = new ButtonGroup();
        languageGroup.add(ru);
        languageGroup.add(en);
        right.add(segmented(ru, en));

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private static JSeparator separator() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(2, Math.round(ChartBase.baseFont().getSize2D() * 1.8f)));
        return s;
    }

    /** Toggle buttons in one rounded frame, so it is clear that exactly one of them is on. */
    private static JPanel segmented(JToggleButton... buttons) {
        return segmented(0, buttons);
    }

    /** Same, and every button gets the width of the widest one plus extraWidth. */
    private static JPanel segmented(int extraWidth, JToggleButton... buttons) {
        JPanel panel = new JPanel(new GridLayout(1, buttons.length, 0, 0));
        panel.setOpaque(false);
        Color border = UIManager.getColor("Component.borderColor");
        panel.setBorder(BorderFactory.createLineBorder(border != null ? border : Color.GRAY, 1, true));
        int width = 0;
        int height = 0;
        for (JToggleButton b : buttons) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            Dimension d = b.getPreferredSize();
            width = Math.max(width, d.width);
            height = Math.max(height, d.height);
        }
        for (JToggleButton b : buttons) {
            b.setPreferredSize(new Dimension(width + extraWidth, height));
            panel.add(b);
        }
        return panel;
    }

    /** Chart with its own header: the title on the left, the metric switch on the right. */
    private JPanel chartBox(Palette pal) {
        JLabel title = new JLabel(chart.title());
        title.setForeground(pal.text);
        title.setFont(title.getFont().deriveFont(Font.BOLD));

        ButtonGroup group = new ButtonGroup();
        List<JToggleButton> buttons = new ArrayList<>();
        for (Metric m : Metric.values()) {
            JToggleButton button = new JToggleButton(metricName(m), m == metric);
            button.addActionListener(e -> {
                metric = m;
                chart.setMetric(m);
                title.setText(chart.title());
            });
            group.add(button);
            buttons.add(button);
        }

        int extra = Math.round(ChartBase.baseFont().getSize2D() * 1.2f);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(pal.chartBg);
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        header.add(title, BorderLayout.WEST);
        header.add(segmented(extra, buttons.toArray(new JToggleButton[0])), BorderLayout.EAST);

        JPanel box = new JPanel(new BorderLayout());
        box.setBackground(pal.chartBg);
        box.add(header, BorderLayout.NORTH);
        box.add(chart, BorderLayout.CENTER);
        return box;
    }

    // ---- export ----------------------------------------------------------------------------

    private void exportZip() {
        if (vm == null || vm.episodes.isEmpty() && !vm.hasSelection()) {
            JOptionPane.showMessageDialog(frame,
                    lang.t("No episodes to export. Drag over the strips to choose an interval.",
                            "Нет эпизодов для выгрузки. Выделите интервал мышкой на полосах."),
                    "sysmon", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final Recording rec = vm.recording;
        final List<Exporter.Piece> pieces = Exporter.choose(vm.episodes,
                episodeList == null ? List.of() : episodeList.getSelectedValuesList(),
                vm.hasSelection(), vm.from(), vm.to());

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(lang.t("Export to zip", "Выгрузка в zip"));
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
        chooser.setSelectedFile(Exporter.defaultZip().toFile());
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        final Path target = chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? chosen : chosen.resolveSibling(chosen.getFileName() + ".zip");
        if (Files.exists(target) && JOptionPane.showConfirmDialog(frame,
                lang.t("Replace the existing file?", "Заменить существующий файл?") + "\n" + target,
                "sysmon", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        final Lang exportLang = lang;
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return Exporter.write(rec, pieces, Exporter.PAD_MS, exportLang, target);
            }

            @Override
            protected void done() {
                try {
                    afterExport(target, get());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(frame,
                            cause.getMessage() != null ? cause.getMessage() : cause.toString(),
                            "sysmon", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void afterExport(Path target, int folders) {
        Object[] options = {lang.t("Show in folder", "Показать в папке"), "OK"};
        int answer = JOptionPane.showOptionDialog(frame,
                lang.t("Archive saved: ", "Архив сохранён: ") + target
                        + "\n" + lang.t("Folders: ", "Папок: ") + folders,
                "sysmon", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[1]);
        if (answer == 0) {
            showInFolder(target);
        }
    }

    private static void showInFolder(Path file) {
        try {
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.toAbsolutePath().getParent().toFile());
            }
        } catch (IOException | RuntimeException ignored) {
            // the path is in the message anyway
        }
    }

    private String metricName(Metric m) {
        return switch (m) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case READ -> lang.t("Read", "Чтение");
            case WRITE -> lang.t("Write", "Запись");
        };
    }

    private void setDark(boolean value) {
        if (dark == value) {
            return;
        }
        dark = value;
        applyLaf(dark);
        FlatLaf.updateUI();
        rebuild();
    }

    private void setLanguage(boolean ru) {
        if (lang.ru() == ru) {
            return;
        }
        lang = new Lang(ru);
        if (tray != null) {
            tray.setLang(lang);
        }
        rebuild();
    }

    private void rebuild() {
        if (vm != null) {
            buildContent();
        } else {
            lastMessage = "";
            reload();
        }
    }

    private void setLive(boolean value) {
        live = value;
        if (liveBox != null) {
            liveBox.setSelected(value);
        }
        if (live) {
            liveTimer.restart();
        } else {
            liveTimer.stop();
        }
    }

    private void chooseDir() {
        JFileChooser chooser = new JFileChooser(dir.toAbsolutePath().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            dir = chooser.getSelectedFile().toPath();
            vm = null;
            lastMessage = "";
            frame.setTitle(title());
            reload();
        }
    }

    // ---- recording ----------------------------------------------------------------------------

    private boolean isRecording() {
        return session != null && session.isRunning();
    }

    private void refreshViewOnly() {
        viewOnly = !isRecording() && RecordingSession.isLocked(dir);
    }

    private void showStartDialog() {
        if (isRecording()) {
            return;
        }
        refreshViewOnly();
        if (viewOnly) {
            JOptionPane.showMessageDialog(frame,
                    lang.t("Recording is already running in another program for this folder. Showing the data only.",
                            "В этот каталог уже идёт запись в другой программе. Показываю только данные."),
                    "sysmon", JOptionPane.INFORMATION_MESSAGE);
            updateRecordingUi();
            return;
        }
        StartDialog.Result r = StartDialog.show(frame, lang, dir);
        if (r != null) {
            startRecording(r);
        }
    }

    private void startRecording(StartDialog.Result r) {
        if (RecordingSession.isLocked(r.dir())) {
            JOptionPane.showMessageDialog(frame,
                    lang.t("Recording is already running in another program for this folder.",
                            "В этот каталог уже идёт запись в другой программе."),
                    "sysmon", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RecordingSession s = new RecordingSession(r.dir(), r.intervalSec(), 25, 50L << 20, 500L << 20, r.duration());
        s.setFinishListener(outcome -> SwingUtilities.invokeLater(() -> onFinished(s, outcome)));
        try {
            s.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "sysmon", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop, "sysmon-shutdown"));
        session = s;
        if (!r.dir().equals(dir)) {
            dir = r.dir();
            vm = null;
            lastMessage = "";
            frame.setTitle(title());
        }
        viewOnly = false;
        setLive(true);
        updateRecordingUi();
        reload();
    }

    private void onFinished(RecordingSession s, RecordingSession.Outcome outcome) {
        if (s != session) {
            return;
        }
        String where = dir.toAbsolutePath().toString();
        switch (outcome) {
            case COMPLETED -> popup(lang.t("Recording finished", "Запись завершена"),
                    lang.t("The set time has passed. Files: ", "Заданное время истекло. Файлы: ") + where);
            case STOPPED -> popup(lang.t("Recording stopped", "Запись остановлена"), where);
            case FAILED -> popup(lang.t("Recording failed", "Ошибка записи"), String.valueOf(s.failure()));
        }
        updateRecordingUi();
        reload();
    }

    private void popup(String title, String message) {
        if (tray != null) {
            tray.popup(title, message);
        } else if (frame.isVisible()) {
            JOptionPane.showMessageDialog(frame, message, title, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void requestStop() {
        if (isRecording()) {
            session.requestStop();
        }
    }

    private void stopAndOpenFolder() {
        requestStop();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (IOException | RuntimeException ignored) {
            // the folder is shown in the title anyway
        }
    }

    private void updateRecordingUi() {
        boolean rec = isRecording();
        if (startButton != null) {
            startButton.setEnabled(!rec && !viewOnly);
        }
        if (stopButton != null) {
            stopButton.setEnabled(rec);
        }
        String text = "";
        if (rec) {
            RecordingSession.Status st = session.status();
            text = "REC " + hms(st.elapsedMs()) + (st.plannedMs() > 0 ? " / " + hms(st.plannedMs()) : "")
                    + " | " + size(st.bytes()) + " | " + lang.t("samples", "замеров") + ": " + st.samples();
        } else if (viewOnly) {
            text = lang.t("Recording runs in another program - view only",
                    "Запись идёт в другой программе - только просмотр");
        }
        if (recLabel != null) {
            recLabel.setText(rec ? "<html><font color='#E0524D'>\u25CF</font> " + text + "</html>" : text);
        }
        if (tray != null) {
            tray.setRecording(rec, rec ? "sysmon - " + text : "sysmon");
        }
    }

    private String size(long bytes) {
        return bytes >= (1 << 20)
                ? String.format(Locale.ROOT, "%.1f %s", bytes / 1048576.0, lang.t("MB", "МБ"))
                : String.format(Locale.ROOT, "%d %s", bytes / 1024, lang.t("KB", "КБ"));
    }

    private static String hms(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60);
    }

    // ---- window lifecycle ---------------------------------------------------------------------

    private void showWindow() {
        frame.setVisible(true);
        frame.setState(Frame.NORMAL);
        frame.toFront();
        frame.requestFocus();
    }

    private void onCloseRequested() {
        boolean recording = isRecording();
        if (recording && tray != null) {
            frame.setVisible(false);
            tray.popup(lang.t("sysmon keeps recording", "sysmon продолжает запись"),
                    lang.t("Stop it and exit from the tray icon menu.", "Остановить и выйти можно из меню значка в трее."));
            return;
        }
        if (recording) {
            int answer = JOptionPane.showConfirmDialog(frame,
                    lang.t("Recording is running. Stop it and exit?", "Идёт запись. Остановить её и выйти?"),
                    "sysmon", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        exit();
    }

    private void exit() {
        liveTimer.stop();
        statusTimer.stop();
        if (session != null) {
            session.stop();
        }
        if (tray != null) {
            tray.remove();
        }
        frame.dispose();
        System.exit(0);
    }

    /** The verdict text with a small copy button in the top right corner, visible while the mouse is over the text. */
    private JPanel verdictPanel(JTextArea verdict) {
        int iconSize = Math.round(ChartBase.baseFont().getSize2D() * 1.2f);
        Icon copyIcon = ActionIcons.copy(iconSize);
        Icon doneIcon = ActionIcons.check(iconSize);

        JButton copy = new JButton(copyIcon);
        copy.setToolTipText(lang.t("Copy text", "Копировать текст"));
        copy.putClientProperty("JButton.buttonType", "toolBarButton");
        copy.setFocusable(false);
        copy.setVisible(false);
        copy.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(verdict.getText()), null);
            } catch (IllegalStateException ignored) {
                return; // the clipboard is busy, nothing was copied
            }
            copy.setIcon(doneIcon);
            copy.setToolTipText(lang.t("Copied", "Скопировано"));
            javax.swing.Timer back = new javax.swing.Timer(1500, ev -> {
                copy.setIcon(copyIcon);
                copy.setToolTipText(lang.t("Copy text", "Копировать текст"));
            });
            back.setRepeats(false);
            back.start();
        });

        // OverlayLayout lines components up by their alignment points: all of them
        // must be anchored to the top right corner, otherwise the button lands in the middle.
        copy.setAlignmentX(1f);
        copy.setAlignmentY(0f);
        verdict.setAlignmentX(1f);
        verdict.setAlignmentY(0f);
        // room on the right so that the button never covers the text
        verdict.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, iconSize + 20));

        JPanel box = new JPanel();
        box.setLayout(new OverlayLayout(box));
        box.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 6));
        box.add(copy);      // the first one is painted on top
        box.add(verdict);

        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                copy.setVisible(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), box);
                if (!box.contains(p)) {
                    copy.setVisible(false);
                }
            }
        };
        box.addMouseListener(hover);
        verdict.addMouseListener(hover);
        copy.addMouseListener(hover);
        return box;
    }
}
