package ru.mcs.sysmon.cli;

import java.util.Locale;

/** Language of console and window text: --lang=ru|en, otherwise the system locale. */
public record Lang(boolean ru) {

    public static Lang of(String code) {
        if (code == null) {
            return new Lang("ru".equals(Locale.getDefault().getLanguage()));
        }
        return new Lang(code.equalsIgnoreCase("ru"));
    }

    public String t(String en, String ruText) {
        return ru ? ruText : en;
    }
}
