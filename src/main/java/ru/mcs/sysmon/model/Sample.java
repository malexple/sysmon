package ru.mcs.sysmon.model;

import java.util.List;

public record Sample(SystemSample system, List<ProcessSample> processes) {
}
