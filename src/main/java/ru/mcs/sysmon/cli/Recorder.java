package ru.mcs.sysmon.cli;

import ru.mcs.sysmon.model.SystemSample;
import ru.mcs.sysmon.recording.RecordingSession;

import java.io.IOException;
import java.util.Locale;

final class Recorder {

    private Recorder() {
    }

    static int run(Args a) throws IOException, InterruptedException {
        RecordingSession session = new RecordingSession(a.out(), a.intervalSec(), a.top(),
                a.maxFileBytes(), a.maxTotalBytes(), a.duration());
        int[] counter = {0};
        session.setSampleListener(s -> {
            if (++counter[0] % 6 == 1) {
                SystemSample x = s.system();
                System.out.printf(Locale.ROOT, "[%d] cpu %.0f%%  mem avail %d/%d MB  disk busy %.0f%% queue %.1f%n",
                        counter[0], x.cpuPct(), x.memAvailMb(), x.memTotalMb(), x.diskBusyPct(), x.diskQueue());
            }
        });
        try {
            session.start();
        } catch (RecordingSession.AlreadyRunningException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(session::stop, "sysmon-shutdown"));
        System.out.printf("Recording every %ds into %s (top %d, file <= %d MB, total <= %d MB%s). Ctrl+C to stop.%n",
                a.intervalSec(), a.out().toAbsolutePath(), a.top(),
                a.maxFileBytes() >> 20, a.maxTotalBytes() >> 20,
                a.duration() == null ? "" : ", stops after " + a.duration());
        session.awaitFinish();
        if (session.failure() != null) {
            System.err.println("Recording failed: " + session.failure());
            return 1;
        }
        System.out.println("Stopped. Files are in " + a.out().toAbsolutePath());
        return 0;
    }
}
