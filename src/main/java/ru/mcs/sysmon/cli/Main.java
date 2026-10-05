package ru.mcs.sysmon.cli;

import java.util.Arrays;

public final class Main {

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        boolean hasCommand = argv.length > 0 && !argv[0].startsWith("--");
        String command = hasCommand ? argv[0] : "record";
        String[] rest = hasCommand ? Arrays.copyOfRange(argv, 1, argv.length) : argv;
        if (argv.length > 0 && (argv[0].equals("-h") || argv[0].equals("--help"))) {
            usage();
            return;
        }
        try {
            int code = switch (command) {
                case "record" -> Recorder.run(Args.parse(rest));
                case "report" -> ReportCommand.run(rest);
                case "ui" -> UiCommand.run(rest);
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

    private static void usage() {
        System.out.println("""
                Usage: java -jar sysmon.jar <command> [options]

                record - write samples
                  --out=samples        output directory (system-*.csv, process-*.csv)
                  --interval=10        seconds between samples
                  --top=25             processes kept per sample, the rest is folded into "(other)"
                  --max-file-mb=50     start a new file after this size
                  --max-total-mb=500   delete the oldest files when the directory exceeds this
                  --duration=8h        stop automatically (s, m, h, d); default: until Ctrl+C

                report - analyse recorded samples in the console
                  --in=samples         directory with the CSV files
                  --lang=ru|en         report language; default: system language
                  --top=10             rows in the process tables
                  --file=report.txt    also save the report (UTF-8)

                ui - open the charts window
                  --in=samples         directory with the CSV files
                  --lang=ru|en         window language; default: system language
                  --theme=dark|light   default: dark
                """);
    }
}
