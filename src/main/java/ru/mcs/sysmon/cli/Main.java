package ru.mcs.sysmon.cli;

import java.util.Arrays;

public final class Main {

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            int code = UiCommand.run(new String[0], true);
            if (code != 0) {
                System.exit(code);
            }
            return;
        }
        if (argv[0].equals("--version") || argv[0].equals("-v") || argv[0].equals("version")) {
            System.out.println("sysmon " + version());
            return;
        }
        if (argv[0].equals("-h") || argv[0].equals("--help") || argv[0].equals("help")) {
            usage();
            return;
        }
        boolean hasCommand = !argv[0].startsWith("--");
        String command = hasCommand ? argv[0] : "record";
        String[] rest = hasCommand ? Arrays.copyOfRange(argv, 1, argv.length) : argv;
        try {
            int code = switch (command) {
                case "record" -> Recorder.run(Args.parse(rest));
                case "report" -> ReportCommand.run(rest);
                case "ui" -> UiCommand.run(rest, false);
                default -> {
                    System.err.println("Unknown command: " + command);
                    usage();
                    yield 1;
                }
            };
            if (code != 0) {
                System.exit(code);
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static String version() {
        Package p = Main.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static void usage() {
        System.out.println("""
                sysmon - what loads your computer, recorded and shown without admin rights

                Usage: java -jar sysmon.jar [command] [options]
                  (no command)   open the window with the "Start recording" dialog

                record - write samples from the console
                  --out=<dir>          output directory (default: ~/sysmon-samples)
                  --interval=10        seconds between samples
                  --top=25             processes kept per sample, the rest is folded into "(other)"
                  --max-file-mb=50     start a new file after this size
                  --max-total-mb=500   delete the oldest files when the directory exceeds this
                  --duration=8h        stop automatically (s, m, h, d); default: until Ctrl+C

                report - analyse recorded samples in the console
                  --in=<dir>           directory with the CSV files (default: ~/sysmon-samples)
                  --lang=ru|en         report language; default: system language
                  --top=10             rows in the process tables
                  --file=report.txt    also save the report (UTF-8)

                ui - open the window on recorded samples
                  --in=<dir>           directory with the CSV files (default: ~/sysmon-samples)
                  --lang=ru|en         window language; default: system language
                  --theme=dark|light   default: dark

                --version              print the version
                """);
    }
}
