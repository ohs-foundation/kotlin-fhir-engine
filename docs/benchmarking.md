# Benchmarking

Measures the engine's CRUD, Search DSL and sync paths across the platforms it ships on. Run
manually; nothing here runs in CI.

## Modules

| Module | What it is |
|---|---|
| `:benchmarks:core` | The workload catalogue and the in-process runner. Every platform runs these same workloads. |
| `:benchmarks:app` | A driver app launched by intent, so Android can be measured as a real app. |
| `:benchmarks:macro` | Macrobenchmark that drives the app and reads trace sections back. |

Workloads are defined once, in `:benchmarks:core`. Neither of the other modules defines its own.

## Quick start

```bash
# Desktop — fastest, no device needed
./gradlew :benchmarks:core:desktopTest -Pbenchmark.profile=standard
```

The report prints as a table and is written to
`benchmarks/core/build/reports/benchmarks/desktop-<timestamp>.json`.

## Desktop

```bash
./gradlew :benchmarks:core:desktopTest                              # standard profile
./gradlew :benchmarks:core:desktopTest -Pbenchmark.profile=smoke    # quick check
./gradlew :benchmarks:core:desktopTest -Pbenchmark.groups=search    # one group
```

| Flag | Default | Meaning |
|---|---|---|
| `-Pbenchmark.profile` | `standard` | `smoke` (10 patients), `standard` (100), `large` (1000) |
| `-Pbenchmark.groups` | `crud,search,sync` | Comma-separated. `server` is never a default. |
| `-Pbenchmark.dataset` | `synthetic` | `synthetic` or `synthea` |
| `-Pbenchmark.warmup` | `2` | Discarded iterations |
| `-Pbenchmark.iterations` | `5` | Measured iterations |
| `-Pbenchmark.seed` | `20260819` | Dataset seed. Changing it changes the fingerprint. |
| `-Pbenchmark.server` | none | Base URL for the `server` group, which is skipped without it |
| `-Pbenchmark.report.dir` | `build/reports/benchmarks` | Where the JSON lands |
| `-Pbenchmark.data.dir` | the packaged output | Where the Synthea corpus is read from |
| `-Pbenchmark.storage.dir` | `build/benchmark-db` | Where the engine's database file goes |

The first six also work on `jsBrowserTest`, `wasmJsBrowserTest` and `iosSimulatorArm64Test`;
`-Pbenchmark.server` works on iOS but not on web. The three directory flags are desktop-only —
[Web](#web) and [iOS](#ios) say where those paths come from instead. Browser runs also have their
own lighter defaults.

## Synthea data

The synthetic dataset is the default and needs no tooling. For realistic numbers, generate Synthea
records instead:

```bash
./gradlew :benchmarks:core:packageBenchmarkData                       # download, generate, package
./gradlew :benchmarks:core:desktopTest -Pbenchmark.dataset=synthea    # both steps, if not yet built
```

The first run downloads a ~200 MB jar into `~/.gradle/caches/synthea/<version>/`, outside the
project so it survives `clean`. The version and its SHA-256 are pinned in `gradle.properties`;
changing either changes the report fingerprint and makes earlier reports incomparable.

**`benchmark.population` means Synthea patients, not synthetic-profile patients.** Synthea generates
full medical histories, so the two scales are nothing alike:

| `-Pbenchmark.population` | Patients | Resources loaded |
|---|---|---|
| `10` (default) | 11 | ~7,200 |
| `100` | 121 | ~122,000 |

Only the resource types the workloads touch are loaded. Synthea also emits `Claim`,
`ExplanationOfBenefit` and `DocumentReference`, which together dwarf everything else and which no
query looks at; loading them exhausts the heap for no benefit.

Synthea's own output is not reproducible file-for-file — it stamps a run timestamp into some
filenames, e.g. `Organization.1787101630467.ndjson` — so packaging merges everything for a type
into `<Type>.ndjson` and writes a `manifest.json` beside it.

**A pinned seed does not give a reproducible corpus.** Two runs at the same `synthea.version`,
`benchmark.seed` and `benchmark.population` have produced different resource counts, and so
different fingerprints. Pinning narrows the variation; it does not remove it. The fingerprint
recorded in the report, not the seed, is what says whether two reports are comparable — regenerate
the dataset only when you are ready to rebaseline.

Parsing is lenient. Synthea emits US Core profiles and extensions the model does not carry, and a
strict parse would reject the corpus. Lines that still fail are counted and printed rather than
silently dropped.

## Android

Use a **physical device**. Emulator numbers are host-bound and meaningless; an emulator is only good
for checking that the plumbing works.

Keep the device awake. Android freezes cached background processes, so if the screen sleeps during a
long workload the run stalls at 0% CPU and the next launch cannot be confirmed. The driver app holds
the screen on and the harness wakes the device, but a device that sleeps for other reasons — low
battery, a policy — will still stall.

### The driver app on its own

```bash
./gradlew :benchmarks:app:installRelease

# One workload
adb shell am start -n dev.ohs.fhir.engine.benchmark.app/.BenchmarkActivity \
  -a dev.ohs.fhir.engine.benchmark.RUN -e workload search.observation_by_code -e profile smoke

# A whole group, which also writes a JSON report
adb shell am start -n dev.ohs.fhir.engine.benchmark.app/.BenchmarkActivity \
  -a dev.ohs.fhir.engine.benchmark.RUN -e groups search -e profile standard

./gradlew :benchmarks:app:pullBenchmarkReports
adb logcat -d -s BenchmarkDriver
```

### Watching a run

A group run shows live progress on the device: which workload is in flight, warmup versus measured
iteration, elapsed time, and each finished workload's median as it lands. The header names the
phase, so the stretch after the last workload reads `reporting` while the JSON is being written
rather than looking like a stall. When the run ends the screen is the results table.

Completions are also written to logcat:

```bash
adb logcat -s BenchmarkDriver
```

A single-workload run (`-e workload <id>`) keeps the plain status text instead, because
macrobenchmark waits on that view and must not pay for a UI. That view reports
`starting` → `ready` → `done`, or `failed <exception>`. `ready` marks the end of untimed setup.

The screen costs the numbers a little. The elapsed clock ticks once a second, and each iteration
event redraws the current row; both can land while a workload is being measured, so the app's own
in-process numbers carry a small amount of UI work. Macrobenchmark drives the single-workload path,
never renders this screen, and is the authoritative Android measurement — treat the app's
group-mode report as indicative.

### Macrobenchmark

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.profile=standard
```

One class at a time:

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ohs.fhir.engine.benchmark.macro.FhirEngineSearchMacrobenchmark
```

Results land in
`benchmarks/macro/build/outputs/connected_android_test_additional_output/`, alongside the Perfetto
traces.

### Synthea on Android

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest -Pbenchmark.dataset=synthea
```

One flag does both jobs: it stages the packaged data into the driver app's assets and tells the run
to use it. Android cannot read the host filesystem, and `/data/local/tmp` is unreadable to an app
from API 30, so the corpus travels inside the APK — only the types a workload queries, about
5.7 MB at `benchmark.population=10`, which compresses to roughly 1 MB of APK.

**Asking for Synthea does not guarantee getting it.** A build without the staged assets falls back
to synthetic silently, and this path writes no report. The driver logs what actually loaded:

```bash
adb logcat -d -s BenchmarkDriver | grep dataset=
# dataset=synthea population=11 fingerprint=6ae5c742bfc713c6
```

`kind=synthetic` there means the assets are missing, whatever you passed on the command line.

**Check that every metric is non-zero before believing a run.** A macrobenchmark passes whether or
not it measured anything, so a green run proves nothing on its own:

```bash
python3 - <<'EOF'
import json, glob
f = glob.glob('benchmarks/macro/build/outputs/connected_android_test_additional_output/'
              'release/connected/*/*-benchmarkData.json')[0]
for b in json.load(open(f))['benchmarks']:
    for k, v in b['metrics'].items():
        if k.endswith('SumMs') and v['median'] == 0:
            print('ZERO:', k)
EOF
```

A zero means the trace section never formed a closed slice. `atrace` pairs begin and end **per
thread**, so anything that lets the measured block resume on a different thread than it started on
breaks the pairing silently — the name still appears in the trace, but nothing matches it. The
Android span therefore runs on one dedicated thread; see `BenchmarkSpan.android.kt`.

**On an emulator**, macrobenchmark refuses to run without:

```bash
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,UNLOCKED,DEBUGGABLE,LOW-BATTERY
```

Suppressing those does not make the numbers meaningful. It only lets the run proceed.

## Web

Needs a Chromium-based browser. Chromium satisfies Karma's `CHROME_BIN` directly:

```bash
export CHROME_BIN=/Applications/Chromium.app/Contents/MacOS/Chromium
./gradlew :benchmarks:core:wasmJsBrowserTest
./gradlew :benchmarks:core:jsBrowserTest
```

Both take the same `-Pbenchmark.*` flags as desktop, `-Pbenchmark.dataset=synthea` included:

```bash
./gradlew :benchmarks:core:jsBrowserTest -Pbenchmark.profile=smoke -Pbenchmark.dataset=synthea
```

With no flags a browser run uses the smoke profile, 1 warmup and 3 measured iterations, rather than
desktop's heavier defaults: a browser over OPFS is the slowest target by a wide margin.

The report lands in `benchmarks/core/build/reports/benchmarks/<js|wasmJs>-<timestamp>.json`,
alongside desktop's. Each workload is also marked on the browser's performance timeline under its
own id, readable with `performance.getEntriesByType("measure")`.

A browser can read neither `-P` properties nor the filesystem, so the Karma server stands in for
both. `benchmarks/core/karma.config.d/benchmark-server.js` serves the run config and the packaged
Synthea data, and receives the finished report. `-Pbenchmark.data.dir` and `-Pbenchmark.report.dir`
therefore do not apply on web; both paths are fixed.

## Sync against a real server

The `server` group is the only way upload is measured at all. `syncUpload` drives patch generation,
patch ordering and bundle generation through `internal` types, so an external caller can only ever
report failure — a real server is not a convenience here, it is the only route.

```bash
benchmarks/tools/start-benchmark-server.sh          # HAPI in docker, waits until it answers
./gradlew :benchmarks:core:packageBenchmarkData     # only needed for server.download
benchmarks/tools/populate-benchmark-server.sh       # ditto

./gradlew :benchmarks:core:desktopTest \
  -Pbenchmark.groups=server \
  -Pbenchmark.server=http://localhost:8080/fhir

benchmarks/tools/stop-benchmark-server.sh
```

`-Pbenchmark.server` can point at any reachable FHIR server; the script is a convenience, not a
requirement. Without the flag the `server` workloads are skipped and the run says so, rather than
passing silently with nothing measured.

| Workload | What it measures |
|---|---|
| `server.upload_creates` | New resources: patch generation, bundling, POST, consolidation |
| `server.upload_updates` | Updates, so the patch generator diffs against a stored resource |
| `server.download` | Download against a real server: paging, parsing, conflict resolution |

**These numbers include the server and the network.** They are comparable only to another run
against the same server on the same machine — never to the server-free `sync` group, and never
across machines. Restart the server between comparable runs: uploads accumulate, and a server with
a million rows answers differently from an empty one.

## iOS

```bash
./gradlew :benchmarks:core:iosSimulatorArm64Test
./gradlew :benchmarks:core:iosSimulatorArm64Test -Pbenchmark.dataset=synthea
```

The report lands in `benchmarks/core/build/reports/benchmarks/ios-<timestamp>.json` like every other
platform's. A simulator shares the host filesystem, so both the report and the Synthea directory are
ordinary host paths handed to the harness through `BENCHMARK_REPORT_DIR` and `BENCHMARK_DATA_DIR`.

A Synthea run on the simulator takes about 20 minutes at `benchmark.population=10`, most of it
seeding the corpus for the fresh-database CRUD workloads.

**Simulator numbers are indicative only.** A simulator runs on the host CPU with the host's disk, so
these say whether the engine works on the platform and roughly where the costs sit — not what an
iPhone would do. A real device needs the data bundled into the test app, which this harness does not
do.

## Reading the results

Every report records the platform and a dataset `fingerprint`. **Two reports are comparable only if
both match.** Different platform, different seed, different population, or a different dataset kind
all mean the numbers are measuring different things.

Per workload the report carries the raw `samplesMillis` plus min/median/p90/max/mean/stdDev and
`medianMillisPerOp`. Prefer the median; `p90` shows how noisy the run was.

### Sanity checks

Before trusting a run:

- **The search medians must show a spread.** If they are all alike and fast, the page cache was
  never disturbed and the search numbers mean nothing. On desktop at `standard` the spread is
  roughly 0.6 ms to 108 ms.
- **Every macrobenchmark `TraceSectionMetric` must be non-zero.** A zero means the section never
  reached the trace — usually a debuggable build, a missing `<profileable>`, or a workload id that
  does not match the span name. This fails silently.
- **`isolationNote` must be null.** When set, the platform could not honour the isolation the
  workload asked for and the number is weaker than it looks.
- **Run the same profile twice.** Median-over-median drift should be well under whatever threshold
  you want to gate on. Macrobenchmark's own variance is roughly 5–10%.

## Known limitations

- **A macrobenchmark passes whether or not it measured anything.** Nothing fails a run whose trace
  sections are all zero, so check the metrics rather than the exit code; see the Android section.

- **Web has no in-process reset.** Closing the database wedges the SQLite Web Worker; not closing
  leaves it holding the exclusive OPFS handle so a reopen never completes. `FRESH_DATABASE`
  therefore degrades to `CLEAR_TABLES` on web, which the report records. A genuinely cold web
  measurement needs a page reload.
- **Upload sync needs a real server.** `syncUpload` expects response mapping types that are
  `internal`, so an external caller can only report failure. The `server` group covers upload
  against a running FHIR server; there is no in-process equivalent.
- **`js`/`wasmJs` have two pre-existing `FhirEngineImplTest` failures** unrelated to benchmarking.
