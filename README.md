# sysmon

[Русский](README.md)

A portable tool that records what actually loads a computer (CPU, memory, disk, processes) and shows it on clear charts. It runs as a single `jar`: no installation, no administrator rights. It started as an answer to "speed up a work laptop on Windows 11 without admin rights": measure first, then decide what to fix, and bring IT concrete numbers.

![sysmon_en.png](.assets/sysmon_en.png)

## Idea: the USE method

For every resource, look at three things (Brendan Gregg's USE method): **utilization** (how busy it is), **saturation** (whether a queue builds up that the resource cannot serve) and **errors**. 5% CPU load does not mean everything is fine: slowdowns usually come from memory saturation (paging) or disk saturation. So sysmon records not only processes but also system saturation metrics, then finds the intervals where a resource was overloaded and shows who was running at that time.

| Resource | Utilization | Saturation |
|---|---|---|
| CPU | whole-machine load, % | no direct run-queue counter, approximated by load above a threshold |
| Memory | used / total, commit | page-ins per second (paging), low free memory |
| Disk | busy time, MB/s | queue length, busy time above a threshold |

## Quick start

```bash
./gradlew shadowJar                                             # builds build/libs/sysmon-<version>.jar
java -jar build/libs/sysmon-<version>.jar                       # window with the "Start recording" dialog
java -jar build/libs/sysmon-<version>.jar record --duration=8h  # record for 8 hours from the console (Ctrl+C stops earlier)
java -jar build/libs/sysmon-<version>.jar report                # console report
java -jar build/libs/sysmon-<version>.jar ui                    # window on recorded data
java -jar build/libs/sysmon-<version>.jar episodes              # numbered list of episodes
java -jar build/libs/sysmon-<version>.jar export                # zip with all episodes
java -jar build/libs/sysmon-<version>.jar --version
```

In the examples below `sysmon.jar` stands for your `sysmon-<version>.jar`. By default data is written to and read from `%USERPROFILE%\sysmon-samples` (`~/sysmon-samples` on other systems). You can open the window while recording is still running: "Live mode" turns on by itself when recording is started from the window.

## Requirements

- To run: Java 17 or newer. To build the jar: Gradle 8.11.1+ (`./gradlew`, JDK 17 as the toolchain).
- Designed for Windows 10/11. The data library (OSHI) is cross-platform, but other systems are untested.
- No administrator rights: sysmon only reads system counters and writes files into its own directory.

## Recording from the window and the tray

Running without arguments (including a double click on the `jar`) opens the window and immediately offers to start recording: duration (1, 4, 8, 12 hours or unlimited), interval and folder. Recording runs in a background thread of the same program.

- The toolbar shows the state: "● REC 00:12:34 / 08:00:00 | 4.2 MB | samples: 74". Live mode turns on automatically.
- Closing the window during recording does not stop it: the program goes to the tray, with a red dot on the icon and a tooltip with the elapsed time and data size. Icon menu: "Open window", "Stop recording", "Stop and open folder", "Exit". When recording ends (including by the timer) a notification appears. If there is no tray, closing the window asks whether to stop recording.
- If another program is already recording into this folder (guarded by the `.recorder.lock` file), the window opens view-only and the "Start recording" button is disabled.

## The `record` command

Console recording without a window, suitable for servers and automation.

| Option | Default | Meaning |
|---|---|---|
| `--out=<dir>` | `~/sysmon-samples` | directory for `system-*.csv` and `process-*.csv` |
| `--interval=10` | 10 | seconds between samples |
| `--top=25` | 25 | processes written per sample; the rest are folded into one `(other)` row |
| `--max-file-mb=50` | 50 | start a new file at this size |
| `--max-total-mb=500` | 500 | delete the oldest files when the total size exceeds this |
| `--duration=8h` | until Ctrl+C | stop automatically (`s`, `m`, `h`, `d`) |

How it works:

- A baseline snapshot is taken at start and recording begins one interval later. Windows counters are cumulative, so the files hold **rates per interval**, not accumulated values.
- The most significant processes are kept. Significance is the largest of the process's shares of total CPU, memory and I/O, so a process with lots of memory and zero CPU is kept just like a CPU hog. All other processes are summed into `(other)`.
- A second recorder cannot start in the same directory (guarded by a `.recorder.lock` file).
- File names: `system-yyyyMMdd-HHmmss-NNN.csv` and `process-yyyyMMdd-HHmmss-NNN.csv`.
- Process paths and command-line arguments are not recorded, only names.
- Running through `javaw` hides the console, but then recording can only be stopped by `--duration` or by killing the process.

## Data format

Numbers use a dot as the decimal separator regardless of regional settings; time is Unix milliseconds. Speeds are stored in KB/s in the files and shown in MB/s in the window and the report.

`system-*.csv` (one row per sample):

| Column | Meaning |
|---|---|
| `ts_ms` | sample time |
| `cpu_pct` | whole-machine load, 0-100 |
| `logical_cpus` | number of logical processors |
| `mem_total_mb`, `mem_avail_mb` | total and available physical memory |
| `commit_used_mb`, `commit_limit_mb` | committed memory and its limit |
| `pages_in_ps`, `pages_out_ps` | pages read in and written out per second |
| `disk_busy_pct` | disk busy time (maximum over disks), 0-100 |
| `disk_queue` | sum of disk queue lengths |
| `disk_read_kbps`, `disk_write_kbps` | disk read and write, KB/s |

`process-*.csv` (up to `top + 1` rows per sample):

| Column | Meaning |
|---|---|
| `ts_ms` | sample time (same as in `system-*.csv`) |
| `pid` | process id, `-1` for the `(other)` row |
| `name` | process name |
| `procs` | how many processes the row stands for (1, more for `(other)`) |
| `cpu_pct` | CPU load as % of the **whole machine** (0-100) |
| `rss_mb` | working set, MB |
| `io_read_kbps`, `io_write_kbps` | process reads and writes, KB/s |

Note: `io_*` is all I/O of the process (files, network, devices), not only disk. Use `system-*.csv` for disk load. The `Idle` process (PID 0) is not recorded: its load is the complement to 100% of the machine `cpu_pct`.

## Saturation episodes

An episode is an interval where a resource was overloaded. Criteria:

| Resource | Condition |
|---|---|
| CPU | load above 85% |
| Memory | free memory below 10% **or** page-ins above 500/s **or** commit above 90% of the limit |
| Disk | busy above 80% **or** queue of at least 2 |

Consecutive samples over the threshold form a run, runs closer than 30 seconds are merged, and an episode counts if it lasts at least 20 seconds (last sample minus first plus one interval, that is two samples in a row at a 10-second interval). Shorter spikes are visible as red bars on the chart but are not episodes.

## The `report` command

```bash
java -jar sysmon.jar report --lang=en --top=10 --file=report.txt
```

| Option | Default | Meaning |
|---|---|---|
| `--in=<dir>` | `~/sysmon-samples` | directory with the CSV files |
| `--lang=ru\|en` | system language | report language |
| `--top=10` | 10 | rows in the process tables |
| `--file=report.txt` | do not save | also save the report as UTF-8 |

Prints the recording header, a machine table (average, 95th percentile, maximum), the list of episodes (time and main processes for each) and the top processes by CPU and by memory. In the process tables reads and writes are separate columns in MB/s. `--file` is handy to attach to an IT request.

Processes are grouped **by name** (all `java` or `vivaldi` are added up). CPU, reads and writes are averaged over all samples of the window (a process missing from the top list was folded into `(other)`), memory is averaged over the samples where the process is present. "RSS swing" is the difference between the maximum and minimum memory of a process in the window; it helps find whose memory grew.

## The `ui` command

```bash
java -jar sysmon.jar ui --lang=en --theme=dark
```

| Option | Default | Meaning |
|---|---|---|
| `--in=<dir>` | `~/sysmon-samples` | directory with the CSV files |
| `--lang=ru\|en` | system language | window language |
| `--theme=dark\|light` | `dark` | theme |

- **Three strips** (CPU, memory, disk) over the whole recording. Bar height is utilization, bar colour is saturation (blue, yellow, red), red background bands are episodes. Gaps in the recording (sleep, restarts) are hatched; the "Compact gaps" switch squeezes long pauses into a narrow hatched stripe.
- **Interval selection:** drag across the strips; a click clears the selection. Everything below shows the selected interval. The tooltip of the disk strip shows busy time, queue and read and write speeds.
- **Verdict line** describes the interval in words: peaks, overlapping episodes, main processes by CPU, by reads and by writes, the biggest memory change. For the whole recording it also says how much time was recorded out of the total span. It is plain text; on hover a small copy icon appears in the top right corner.
- **Episode list** on the left: a click selects the episode interval, Ctrl-click and Shift-click pick several episodes for export (the interval stays as it is).
- **Stacked chart** of the top 5 processes (by name) plus "other". The metric is switched with the CPU, Memory, Read, Write buttons in the chart header (read and write in MB/s), so you can see which programs read a lot and which write a lot. A tooltip shows the values on hover.
- **Process table** for the interval with heat-map shading and sorting. Read and write are separate columns in MB/s.
- **Toolbar** is split into groups. Recording: "Start recording", "Stop" and the REC state. Data: "Open folder...", "Reload", "Live mode" (re-reads the files every 15 seconds without rebuilding the window: sorting and dividers are kept). View: "Compact gaps". On the right: "Export to ZIP..." (disabled while there is no data), the theme switch (sun | moon) and the language switch (RU | EN).

## Exporting episodes to a zip

To show colleagues or IT only the relevant moments instead of the whole directory, use the "Export to ZIP..." button in the window or the `export` command.

What goes into the archive. The window applies these rules in order:

1. the episodes picked in the list on the left (Ctrl-click and Shift-click pick several);
2. if nothing is picked in the list, the interval dragged on the strips. This is for "smeared" slowdowns that do not reach the episode thresholds (for example, the disk busy at 75% for ten minutes);
3. if there is no interval either, all episodes.

Archive layout:

```text
sysmon-episodes-20261008-143015.zip
├── summary.txt                        report on what was exported (like the report command)
├── episodes.csv                       what was exported: folder, resource, start, end, duration
├── episode-1-memory-20-18-57/         episode no. 1, start time in the name
│   ├── system-export.csv
│   └── process-export.csv
├── episode-2-memory-00-09-45/
│   └── ...
└── custom-interval-14-05-46/          instead of an episode folder when a dragged interval was exported
```

- Every folder holds 60 seconds before and after the episode, so you see the state of the system before the slowdown and after it. The window uses a constant, the console can change it with `--pad`.
- Episode numbers are chronological and match the `episodes` command. Times in folder names and in `episodes.csv` are local time.
- Process names stay as they are. The files are rebuilt from the parsed data, so malformed lines do not end up in the archive.
- By default the archive is named `sysmon-episodes-yyyyMMdd-HHmmss.zip` and proposed in `%USERPROFILE%\sysmon-samples`. After saving, the window shows "Archive saved" and a "Show in folder" button (Explorer with the file selected).

To open an archive: unzip it and run `java -jar sysmon.jar ui --in=<unpacked folder>`. The directory is read together with its subfolders (the root and two levels below), so all episodes open in one window with the pauses between them hatched. Samples with the same time from different folders (the padding of neighbouring episodes overlaps) are merged into one.

From the console:

```bash
java -jar sysmon.jar episodes                                    # list of episodes with numbers
java -jar sysmon.jar export --episodes=1,3 --out=incident.zip    # export episodes 1 and 3
java -jar sysmon.jar export                                      # export all episodes
```

| `episodes` option | Default | Meaning |
|---|---|---|
| `--in=<dir>` | `~/sysmon-samples` | directory with the CSV files |
| `--lang=ru\|en` | system language | output language |

| `export` option | Default | Meaning |
|---|---|---|
| `--in=<dir>` | `~/sysmon-samples` | directory with the CSV files |
| `--episodes=all\|1,3` | `all` | numbers from the `episodes` list |
| `--out=<file.zip>` | `~/sysmon-samples/sysmon-episodes-<date-time>.zip` | where to save the archive |
| `--pad=60s` | 60s | context before and after every episode (`s`, `m`, `h`, `d`) |
| `--lang=ru\|en` | system language | language of `summary.txt` |

The `episodes` command prints one line per episode: number, start and end, resource, duration and the main processes by CPU. Format example (the values are illustrative):

```text
Saturation episodes in C:\Users\user\sysmon-samples: 2
  1  2026-10-05 20:18:57 - 20:19:48  Memory     60 s  idea64 12.9%, MsMpEng 7.5%
  2  2026-10-06 00:09:45 - 00:13:26  Memory    231 s  idea64 11.0%
Export: export --episodes=1,3
```

An arbitrary interval (not an episode) can be exported from the window only.

## Example finding

A recording on a developer laptop: a project build ran at 20:18-20:20. The window showed a 60-second memory episode: page-ins up to 2145/s, disk busy up to 15%, and `MsMpEng` (Microsoft Defender) reading 8-12 MB/s at 6-9% of machine CPU. That is consistent with the antivirus scanning files that the build writes. This is exactly the kind of evidence you can attach to a request for scan exclusions for build directories and caches.

## Limitations

- There is no direct CPU run-queue counter; CPU saturation is approximated by high load.
- Process reads and writes are not disk I/O (see above). Disk busy time and queue come from Windows counters through OSHI; on a given machine it is worth checking them under load (copy a large file and see whether `disk_busy_pct` and `disk_queue` rise).
- Small processes end up in `(other)`: raise `--top` for a more detailed view.
- `report` and `ui` read the whole directory into memory. That is fine for several days of recording; hundreds of megabytes would need streaming loading. Subfolders are read two levels deep below the chosen directory.
- The working set (`rss_mb`) includes shared pages, so the sum over processes can exceed the physical memory in use.
- On Java 17 the Cyrillic console output of `report` may be garbled on Windows: use `--file=report.txt` (UTF-8) or `--lang=en`.
- macOS and Linux are untested: some metrics (for example disk busy time and paging) may be unavailable there. The tray icon on Windows 11 may be in the hidden area (the "^" arrow), and notifications depend on system settings.

## Querying the CSV with SQL (optional)

You can analyse the CSV with any tool that runs SQL over CSV, for example the **CSV Query** Notepad++ plugin (the table is called `this`, the query must be a single line, values are stored as text, so use `CAST`). Example: when paging happened (file `system-*.csv`)

```sql
SELECT ts_ms, cpu_pct, mem_avail_mb, pages_in_ps, disk_busy_pct FROM this WHERE CAST(pages_in_ps AS REAL) > 500 ORDER BY ts_ms
```

![system.png](.assets/system.png)

**Recording summary.** Shows how heavy the machine was: the number of samples, average and maximum CPU load, minimum free memory, maximum paging and disk busy time. A quick answer to "was there anything serious at all".

```sql
SELECT COUNT(*) AS samples, ROUND(AVG(CAST(cpu_pct AS REAL)),1) AS avg_cpu, ROUND(MAX(CAST(cpu_pct AS REAL)),1) AS max_cpu, ROUND(MIN(CAST(mem_avail_mb AS REAL)),0) AS min_free_mb, ROUND(MAX(CAST(pages_in_ps AS REAL)),0) AS max_pages_in, ROUND(MAX(CAST(disk_busy_pct AS REAL)),1) AS max_disk_busy FROM this
```

**Moments of memory shortage.** Rows where paging is above 500 pages per second (the memory threshold used for episodes). Consecutive rows form one episode, and the time helps you find it in the window or in `process-*.csv`.

```sql
SELECT datetime(CAST(ts_ms AS INTEGER)/1000,'unixepoch','localtime') AS time, ROUND(CAST(pages_in_ps AS REAL),0) AS pages_in, mem_avail_mb, ROUND(CAST(disk_busy_pct AS REAL),1) AS disk_busy, ROUND(CAST(cpu_pct AS REAL),1) AS cpu FROM this WHERE CAST(pages_in_ps AS REAL) > 500 ORDER BY CAST(ts_ms AS INTEGER)
```

**Moments of disk load.** Samples where the disk is busy more than 50% or the queue is at least 1, with read and write speed (in KB/s, as in the file). It is also a check of the disk counters: if you copy a large file and there is not a single row here, the counter on this machine most likely does not work.

```sql
SELECT datetime(CAST(ts_ms AS INTEGER)/1000,'unixepoch','localtime') AS time, ROUND(CAST(disk_busy_pct AS REAL),1) AS busy, disk_queue, ROUND(CAST(disk_read_kbps AS REAL),0) AS read_kbps, ROUND(CAST(disk_write_kbps AS REAL),0) AS write_kbps FROM this WHERE CAST(disk_busy_pct AS REAL) > 50 OR CAST(disk_queue AS REAL) >= 1 ORDER BY CAST(ts_ms AS INTEGER)
```

Example: average CPU load per process name over the whole recording (file `process-*.csv`)

```sql
SELECT name, ROUND(SUM(CAST(cpu_pct AS REAL)) / (SELECT COUNT(DISTINCT ts_ms) FROM this), 2) AS avg_cpu FROM this GROUP BY name ORDER BY avg_cpu DESC LIMIT 20
```

![process.png](.assets/process.png)

**Who reads and writes the most.** Average process read and write speeds in MB/s over the whole recording, as separate columns. It is divided by the number of all samples, not only those where the process made it into the top list, so rare processes are not inflated. Reminder: this is all I/O of the process, not only disk.

```sql
SELECT name, ROUND(SUM(CAST(io_read_kbps AS REAL)) / 1024 / (SELECT COUNT(DISTINCT ts_ms) FROM this), 2) AS avg_read_mbps, ROUND(SUM(CAST(io_write_kbps AS REAL)) / 1024 / (SELECT COUNT(DISTINCT ts_ms) FROM this), 2) AS avg_write_mbps FROM this GROUP BY name ORDER BY avg_read_mbps + avg_write_mbps DESC LIMIT 20
```

**Memory per process name and its growth.** The inner query first adds up the memory of all processes with the same name in every sample (all `vivaldi` tabs, all `java`), the outer one computes the average, maximum and swing (`swing_mb`) across samples. A large swing means the memory of a process grew or dropped noticeably, a hint of who is "inflating".

```sql
SELECT name, ROUND(AVG(mem),0) AS avg_mb, ROUND(MAX(mem),0) AS max_mb, ROUND(MAX(mem)-MIN(mem),0) AS swing_mb FROM (SELECT ts_ms, name, SUM(CAST(rss_mb AS REAL)) AS mem FROM this GROUP BY ts_ms, name) GROUP BY name ORDER BY avg_mb DESC LIMIT 20
```

**Who was running during the chosen minutes.** Substitute the start and end of the interval (local time) taken from the previous queries or from an episode in the window. The result is the average CPU and the read and write speeds in MB/s per process for that period, that is, what the table in the window shows, but in SQL. The time appears several times because the divisor must be counted over all samples of the interval.

```sql
SELECT name, ROUND(SUM(CAST(cpu_pct AS REAL)) / (SELECT COUNT(DISTINCT ts_ms) FROM this WHERE datetime(CAST(ts_ms AS INTEGER)/1000,'unixepoch','localtime') BETWEEN '2026-10-05 20:18:57' AND '2026-10-05 20:19:48'), 2) AS avg_cpu, ROUND(SUM(CAST(io_read_kbps AS REAL)) / 1024 / (SELECT COUNT(DISTINCT ts_ms) FROM this WHERE datetime(CAST(ts_ms AS INTEGER)/1000,'unixepoch','localtime') BETWEEN '2026-10-05 20:18:57' AND '2026-10-05 20:19:48'), 2) AS avg_read_mbps, ROUND(SUM(CAST(io_write_kbps AS REAL)) / 1024 / (SELECT COUNT(DISTINCT ts_ms) FROM this WHERE datetime(CAST(ts_ms AS INTEGER)/1000,'unixepoch','localtime') BETWEEN '2026-10-05 20:18:57' AND '2026-10-05 20:19:48'), 2) AS avg_write_mbps FROM this WHERE datetime(CAST(ts_ms AS INTEGER)/1000,'unixepoch','localtime') BETWEEN '2026-10-05 20:18:57' AND '2026-10-05 20:19:48' GROUP BY name ORDER BY avg_cpu DESC LIMIT 10
```

## Project layout

```text
sysmon/
├── build.gradle
├── settings.gradle
└── src/
    ├── main/java/ru/mcs/sysmon/
    │   ├── cli/        Main, record, report, episodes, export (Exporter), ui, argument parsing, language
    │   ├── collector/  Sampler (OSHI), top-N selection
    │   ├── model/      SystemSample, ProcessSample, Sample
    │   ├── recording/  RecordingSession: recording on a background thread (console and window)
    │   ├── storage/    CSV writer with rotation and a disk quota
    │   ├── analysis/   recording loader, saturation episodes, per-name aggregation
    │   └── ui/         Swing + FlatLaf window, Java2D charts, icons, tray, recording dialog
    └── test/
        ├── java/...    tests for rotation, top-N, episodes, recording, report, export and window
        └── resources/sample/  a real recording used by the tests
```

Tests: `./gradlew test`. The chart components are checked headless (rendered into an image); the look of the window is checked by eye.

## License

sysmon is released under the [MIT License](LICENSE): you may use, modify and distribute it freely as long as the copyright notice is kept.

The jar bundles third-party libraries (OSHI, JNA, FlatLaf, SLF4J API) under their own licenses; they are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
