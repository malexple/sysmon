package ru.mcs.sysmon.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

record Args(Path out, int intervalSec, int top, long maxFileBytes, long maxTotalBytes, Duration duration) {

    static Map<String, String> keyValues(String[] argv) {
        Map<String, String> m = new HashMap<>();
        for (String a : argv) {
            if (!a.startsWith("--") || !a.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, got: " + a);
            }
            String[] kv = a.substring(2).split("=", 2);
            m.put(kv[0], kv[1]);
        }
        return m;
    }

    static Args parse(String[] argv) {
        Map<String, String> m = keyValues(argv);
        Args args = new Args(
                Path.of(m.getOrDefault("out", "samples")),
                intArg(m, "interval", 10),
                intArg(m, "top", 25),
                intArg(m, "max-file-mb", 50) * 1024L * 1024L,
                intArg(m, "max-total-mb", 500) * 1024L * 1024L,
                m.containsKey("duration") ? Durations.parse(m.get("duration")) : null);
        if (args.intervalSec < 1 || args.top < 1 || args.maxFileBytes < 1024 * 1024L) {
            throw new IllegalArgumentException("interval and top must be >= 1, max-file-mb must be >= 1");
        }
        if (args.maxTotalBytes < 2 * args.maxFileBytes) {
            throw new IllegalArgumentException("max-total-mb must be at least twice max-file-mb");
        }
        return args;
    }

    private static int intArg(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got: " + v);
        }
    }
}
