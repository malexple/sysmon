package ru.mcs.sysmon.cli;

import java.time.Duration;

final class Durations {

    private Durations() {
    }

    /** "90s", "30m", "8h", "2d". */
    static Duration parse(String text) {
        if (text == null || text.length() < 2) {
            throw new IllegalArgumentException("Bad duration: " + text + " (use e.g. 30m, 8h)");
        }
        long n;
        try {
            n = Long.parseLong(text.substring(0, text.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad duration: " + text + " (use e.g. 30m, 8h)");
        }
        return switch (text.charAt(text.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Bad duration: " + text + " (use e.g. 30m, 8h)");
        };
    }
}
