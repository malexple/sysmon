# Third-party notices

The sysmon jar bundles the libraries below. sysmon itself is licensed under the MIT License (see `LICENSE`).
Each library keeps its own license; the jar contains their classes unmodified.

| Library | Used for | License |
|---|---|---|
| [OSHI](https://github.com/oshi/oshi) (`com.github.oshi:oshi-core`) | reading CPU, memory, disk and process counters | MIT |
| [JNA](https://github.com/java-native-access/jna) (`net.java.dev.jna`, pulled in by OSHI) | native calls for OSHI | dual-licensed: LGPL 2.1 or later, or Apache License 2.0. sysmon uses it under the Apache License 2.0 |
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf) (`com.formdev:flatlaf`) | modern look and feel of the Swing window | Apache License 2.0 |
| [SLF4J API](https://www.slf4j.org/) (`org.slf4j:slf4j-api`, pulled in by OSHI) | logging facade used by OSHI | MIT |

Full license texts:

- MIT License: https://opensource.org/license/mit
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt

Test-only dependencies (JUnit) and build tools (Gradle, the Shadow plugin) are not part of the distributed jar.

To check the exact list of bundled libraries for a given build:

```bash
./gradlew dependencies --configuration runtimeClasspath
```
