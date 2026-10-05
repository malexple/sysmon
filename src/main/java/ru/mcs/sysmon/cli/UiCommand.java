package ru.mcs.sysmon.cli;

import ru.mcs.sysmon.ui.SysmonWindow;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Map;

final class UiCommand {

    private UiCommand() {
    }

    /** offerRecording: show the "Start recording" dialog right after the window opens. */
    static int run(String[] argv, boolean offerRecording) {
        Map<String, String> o = Args.keyValues(argv);
        Path dir = Path.of(o.getOrDefault("in", Defaults.outDir().toString()));
        Lang lang = Lang.of(o.get("lang"));
        boolean dark = !"light".equalsIgnoreCase(o.getOrDefault("theme", "dark"));
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(lang.t("No display available for the window.", "Нет графического окружения для окна."));
            return 1;
        }
        SysmonWindow.launch(dir, lang, dark, offerRecording);
        return 0;
    }
}
