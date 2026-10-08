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
import javax.swing.JOptionPane;
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
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
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
        frame.setTitle(title());
        frame.revalidate();
        frame.repaint();
        updateRecordingUi();
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
        JList<Episode> list = new JList<>(episodeModel);
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
            if (!ev.getValueIsAdjusting() && e != null && vm != null) {
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

        startButton = new JButton(lang.t("\u25CF Start recording", "\u25CF Начать запись"));
        startButton.addActionListener(e -> showStartDialog());
        stopButton = new JButton(lang.t("\u25A0 Stop", "\u25A0 Остановить"));
        stopButton.addActionListener(e -> requestStop());
        recLabel = new JLabel();
        recLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        tb.add(startButton);
        tb.add(stopButton);
        tb.add(recLabel);
        tb.addSeparator();

        JButton open = new JButton(lang.t("Open folder...", "Открыть папку..."));
        open.addActionListener(e -> chooseDir());
        JButton refresh = new JButton(lang.t("Reload", "Обновить"));
        refresh.addActionListener(e -> reload());
        liveBox = new JCheckBox(lang.t("Live (15 s)", "Живой режим (15 с)"), live);
        liveBox.addActionListener(e -> setLive(liveBox.isSelected()));
        JCheckBox compressBox = new JCheckBox(lang.t("Compact gaps", "Сжать пропуски"), compress);
        compressBox.addActionListener(e -> {
            compress = compressBox.isSelected();
            if (vm != null) {
                vm.setCompressGaps(compress);
            }
        });
        tb.add(open);
        tb.add(refresh);
        tb.add(liveBox);
        tb.add(compressBox);
        tb.addSeparator();

        tb.add(new JLabel(lang.t("Chart: ", "График: ")));
        ButtonGroup group = new ButtonGroup();
        for (Metric m : Metric.values()) {
            JToggleButton button = new JToggleButton(metricName(m), m == metric);
            button.addActionListener(e -> {
                metric = m;
                if (chart != null && vm != null) {
                    chart.setMetric(m);
                }
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
            if (tray != null) {
                tray.setLang(lang);
            }
            rebuild();
        });
        tb.add(theme);
        tb.add(language);
        return tb;
    }

    private String metricName(Metric m) {
        return switch (m) {
            case CPU -> "CPU";
            case MEMORY -> lang.t("Memory", "Память");
            case READ -> lang.t("Read", "Чтение");
            case WRITE -> lang.t("Write", "Запись");
        };
    }

    private void toggleTheme() {
        dark = !dark;
        applyLaf(dark);
        FlatLaf.updateUI();
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
}
