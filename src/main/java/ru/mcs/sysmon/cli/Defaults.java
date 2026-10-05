package ru.mcs.sysmon.cli;

import java.nio.file.Path;

final class Defaults {

    private Defaults() {
    }

    /** Where recordings go when no directory is given: always writable, needs no admin rights. */
    static Path outDir() {
        return Path.of(System.getProperty("user.home"), "sysmon-samples");
    }
}
