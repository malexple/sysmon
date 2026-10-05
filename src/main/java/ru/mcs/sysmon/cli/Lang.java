package ru.mcs.sysmon.cli;

import java.util.Locale;

/** Picks the language of console output: --lang=ru|en, otherwise the system locale. */
record Lang(boolean ru) {

    static Lang of(String code) {
        if (code == null) {
            return new Lang("ru".equals(Locale.getDefault().getLanguage()));
        }
        return new Lang(code.equalsIgnoreCase("ru"));
    }

    String t(String en, String ruText) {
        return ru ? ruText : en;
    }
}
