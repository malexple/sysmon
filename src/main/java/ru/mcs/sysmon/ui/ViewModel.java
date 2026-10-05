package ru.mcs.sysmon.ui;

import ru.mcs.sysmon.analysis.Episode;
import ru.mcs.sysmon.analysis.EpisodeDetector;
import ru.mcs.sysmon.analysis.Recording;
import ru.mcs.sysmon.model.SystemSample;

import java.util.ArrayList;
import java.util.List;

/**
 * What the window shows: the recording, its episodes and the time interval selected on the strips.
 * The data can be replaced in place (live mode); listeners are told about every change.
 */
final class ViewModel {

    Recording recording;
    List<Episode> episodes;
    long t0;
    long t1;
    long interval;
    long tEnd;
    /** Grows with every data update so views can refresh their caches. */
    int version;

    private boolean compress;
    private TimeScale scale;
    private boolean selected;
    private long selFrom;
    private long selTo;
    private final List<Runnable> listeners = new ArrayList<>();

    ViewModel(Recording recording, List<Episode> episodes) {
        apply(recording, episodes);
    }

    private void apply(Recording rec, List<Episode> eps) {
        this.recording = rec;
        this.episodes = eps;
        List<SystemSample> sys = rec.system();
        this.t0 = sys.get(0).tsMs();
        this.t1 = sys.get(sys.size() - 1).tsMs();
        long median = EpisodeDetector.medianInterval(sys);
        this.interval = median > 0 ? median : 1000;
        this.tEnd = t1 + interval;
        this.scale = new TimeScale(sys.stream().mapToLong(SystemSample::tsMs).toArray(), interval, compress);
    }

    /** Replaces the data keeping the selection (clamped to the new range). */
    void update(Recording rec, List<Episode> eps) {
        apply(rec, eps);
        version++;
        if (selected) {
            selFrom = Math.max(t0, selFrom);
            selTo = Math.min(t1, selTo);
            if (selFrom > selTo) {
                selected = false;
            }
        }
        fire();
    }

    TimeScale scale() {
        return scale;
    }

    boolean compressGaps() {
        return compress;
    }

    void setCompressGaps(boolean value) {
        if (compress == value) {
            return;
        }
        compress = value;
        List<SystemSample> sys = recording.system();
        scale = new TimeScale(sys.stream().mapToLong(SystemSample::tsMs).toArray(), interval, compress);
        fire();
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
