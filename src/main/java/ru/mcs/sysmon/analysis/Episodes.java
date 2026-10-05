package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.SystemSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Episodes {

    private Episodes() {
    }

    /** Saturation episodes of all three resources with the standard thresholds, ordered by start. */
    public static List<Episode> detectAll(List<SystemSample> samples) {
        List<Episode> all = new ArrayList<>();
        all.addAll(EpisodeDetector.detect(samples, Resource.CPU, Thresholds::cpu,
                Thresholds.MIN_EPISODE_MS, Thresholds.MERGE_GAP_MS));
        all.addAll(EpisodeDetector.detect(samples, Resource.MEMORY, Thresholds::memory,
                Thresholds.MIN_EPISODE_MS, Thresholds.MERGE_GAP_MS));
        all.addAll(EpisodeDetector.detect(samples, Resource.DISK, Thresholds::disk,
                Thresholds.MIN_EPISODE_MS, Thresholds.MERGE_GAP_MS));
        all.sort(Comparator.comparingLong(Episode::startMs));
        return all;
    }
}
