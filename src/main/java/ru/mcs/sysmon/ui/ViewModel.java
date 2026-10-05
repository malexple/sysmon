package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.EpisodeDetector;
import ru.mcs.sysmon.analysis.Recording;

import java.util.ArrayList;
import java.util.List;

/** What the window shows: the recording, its episodes and the time interval selected on the strips. */
final class ViewModel {

    final Recording recording;
    final List<Episode> episodes;
    final long t0;
    final long t1;
    final long interval;
    final long tEnd;

    private boolean selected;
    private long selFrom;
    private long selTo;
    private final List<Runnable> listeners = new ArrayList<>();

    ViewModel(Recording recording, List<Episode> episodes) {
        this.recording = recording;
        this.episodes = episodes;
        this.t0 = recording.system().get(0).tsMs();
        this.t1 = recording.system().get(recording.system().size() - 1).tsMs();
        long median = EpisodeDetector.medianInterval(recording.system());
        this.interval = median > 0 ? median : 1000;
        this.tEnd = t1 + interval;
    }

    boolean hasSelection() {
        return selected;
    }

    long from() {
        return selected ? selFrom : t0;
    }

    long to() {
        return selected ? selTo : t1;
    }

    void select(long a, long b) {
        selFrom = Math.max(t0, Math.min(a, b));
        selTo = Math.min(t1, Math.max(a, b));
        selected = true;
        fire();
    }

    void clearSelection() {
        if (selected) {
            selected = false;
            fire();
        }
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void clearListeners() {
        listeners.clear();
    }

    private void fire() {
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }
}
