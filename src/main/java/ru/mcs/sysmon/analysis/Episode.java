package ru.mcs.sysmon.analysis;

import ru.mcs.sysmon.model.SystemSample;

import java.util.List;

/** A period where one resource was saturated. samples covers startMs..endMs inclusive. */
public record Episode(Resource resource, long startMs, long endMs, long durationMs, List<SystemSample> samples) {
}
