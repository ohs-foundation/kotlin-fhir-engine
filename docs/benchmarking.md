# Benchmarking

These benchmarks measure the KMP engine's CRUD, Search DSL and sync paths on every platform it ships
on. You run them by hand. Only the micro benchmarks run in CI.

## Modules

| Module | What it is |
|---|---|
| `:benchmarks:core` | The workload catalogue and the in-process runner. Every platform runs these same workloads. |
| `:benchmarks:app` | A driver app started by an intent, so Android measures the engine inside a real app. |
| `:benchmarks:macro` | The macrobenchmark that drives the app and reads its trace sections. |
| `:engine` (`desktopBenchmark`) | JMH micro benchmarks of the engine's internals and of SQLite itself. See [Micro benchmarks](#micro-benchmarks). |

Every workload is defined once, in `:benchmarks:core`. The other modules define none of their own.

## Quick start

```bash
# Desktop. The fastest option, and it needs no device.
./gradlew :benchmarks:core:desktopTest -Pbenchmark.profile=standard
```

The report prints as a table. It is also written to
`benchmarks/core/build/reports/benchmarks/desktop-<timestamp>.json`.

## Desktop

```bash
./gradlew :benchmarks:core:desktopTest                              # standard profile
./gradlew :benchmarks:core:desktopTest -Pbenchmark.profile=smoke    # quick check
./gradlew :benchmarks:core:desktopTest -Pbenchmark.groups=search    # one group
./gradlew :benchmarks:core:desktopTest -Pbenchmark.workloads=crud.get_by_id   # one workload
```

| Flag | Default | Meaning |
|---|---|---|
| `-Pbenchmark.profile` | `standard` | `smoke` (10 patients), `standard` (100), `large` (1000) |
| `-Pbenchmark.groups` | `crud,search,sync` | Comma-separated. `server` is never a default. |
| `-Pbenchmark.dataset` | `synthetic` | `synthetic` or `synthea` |
| `-Pbenchmark.warmup` | `2` | Iterations that run but are not counted |
| `-Pbenchmark.iterations` | `5` | Measured iterations |
| `-Pbenchmark.seed` | `20260819` | Dataset seed. Changing it changes the fingerprint. |
| `-Pbenchmark.workloads` | none | Comma-separated workload ids. Overrides `groups`. The fastest way to measure one thing. |
| `-Pbenchmark.population` | from the profile | Patient count. Use it to measure several sizes without adding a profile, for example `500`. |
| `-Pbenchmark.mix` | `8x2` | Generated clinical resources per patient, as `<observations>x<conditions>`, or `off`. |
| `-Pbenchmark.coldcache` | `false` | Reopen the database between iterations of read-only workloads, so they do not read warm pages. |
| `-Pbenchmark.server` | none | Base URL for the `server` group. Without it, the group is skipped. |
| `-Pbenchmark.report.dir` | `build/reports/benchmarks` | Where the JSON report is written |
| `-Pbenchmark.data.dir` | the packaged output | Where the Synthea corpus is read from |
| `-Pbenchmark.storage.dir` | `build/benchmark-db` | Where the engine's database file goes |

The first ten flags also work on `jsBrowserTest`, `wasmJsBrowserTest` and `iosSimulatorArm64Test`.
Web cannot reopen a database, so on web `-Pbenchmark.coldcache` is recorded in the report but has no
effect. `-Pbenchmark.server` works on Android and iOS, but not on web.

The three directory flags work on desktop only. The [Web](#web) and [iOS](#ios) sections say where
those paths come from instead.

Web and iOS use lighter defaults than desktop: the `smoke` profile, 1 warmup and 3 measured
iterations.

## Synthea data

The synthetic dataset is the default and needs no tooling. For realistic numbers, generate Synthea
records instead:

```bash
./gradlew :benchmarks:core:packageBenchmarkData                       # download, generate, package
./gradlew :benchmarks:core:desktopTest -Pbenchmark.dataset=synthea    # packages first if needed
```

If you ask for `synthea` and the corpus is missing or holds no `Patient`, the run fails. No platform
measures the synthetic data in its place.

The first run downloads a jar of about 200 MB into `~/.gradle/caches/synthea/<version>/`. That
folder is outside the project, so the jar survives `clean`. The version and its SHA-256 are pinned in
`gradle.properties`. Changing either one changes the report fingerprint, so earlier reports are no
longer comparable.

### Regenerating deletes the packaged corpus

`generateSyntheaData` deletes its output before it runs. The population comes from
`synthea.population`, which defaults to `10`. So a command that forgets the flag would replace a
50,000-patient corpus that took half an hour to build. `guardBenchmarkCorpus` stops the build
instead:

```
A benchmark corpus of 50000 patients is already packaged, but this run asks for 10 and
generating would delete it. Pass -Psynthea.population=50000 to use what is there, or
-Pbenchmark.regenerate to replace it.
```

### What the corpus contains, and what is generated

With every module, Synthea produces about 131 resources per patient. That is roughly 5 GB at 50,000
patients, and more than half a day to insert on a benchmark tablet. Most of it is observations that
no query needs in that volume.

So Synthea runs with one module (`-m pregnancy`) and `--exporter.fhir.included_resources=Patient`.
The corpus then holds patients, about one encounter per patient, and Synthea's organizations and
practitioners. For example, `synthea.population=10` gives 10 Patients, 10 Encounters, 27
Organizations, 27 Practitioners and 1 Location. The Location is not loaded.

The observations and conditions that the search workloads query are generated in code, against the
corpus's real patient ids (`AugmentedDataset`). This repository sets the clinical volume per
patient, not Synthea. The default is 8 observations and 2 conditions. `-Pbenchmark.mix` changes it.
For example, `12x3` generates more, and `off` generates nothing so the run measures the corpus
alone.

| `-Psynthea.population` | From the corpus | Generated | Total loaded |
|---|---|---|---|
| `10` (default) | ~75 | 100 | ~175 |
| `500` | ~2,350 | 5,000 | ~7,350 |
| `50000` | ~167,000 | 500,000 | ~667,000 |

The report records the mix. The dataset kind becomes `synthea+generated`, and the fingerprint
includes the mix. Two runs over the same corpus with different mixes therefore never look
comparable.

Only the resource types the workloads query are loaded. `NdjsonDataset.INCLUDED_TYPES` lists them.

### Reading a corpus larger than memory

The corpus is streamed. It is never held in memory. A 50,000-patient corpus parses to more than the
256 MB heap a normal Android app gets. So `NdjsonDataset` keeps only the counts, the patient ids and
a fingerprint, and reads the files again whenever it needs the resources.

That scan still takes minutes at 50,000 patients, and every macrobenchmark iteration is a new
process. So the scan result is cached next to the app, keyed by the corpus manifest. Only the first
process pays for the scan.

Synthea's file names change between runs, because some include a run timestamp, for example
`Organization.1787101630467.ndjson`. Packaging therefore merges each type into one
`<Type>.ndjson` and writes a `manifest.json` next to them.

### A pinned seed does not give a reproducible corpus

Two runs with the same `synthea.version`, `benchmark.seed` and `synthea.population` have produced
different resource counts, and so different fingerprints. Pinning reduces the variation but does not
remove it. Use the fingerprint in the report, not the seed, to decide whether two reports are
comparable. Regenerate the dataset only when you are ready to start a new baseline.

Parsing is lenient. Synthea emits US Core profiles and extensions that the model does not carry, and
a strict parse would reject the corpus. Lines that still fail are counted and printed. They are not
dropped silently.

## Android

Use a physical device. Emulator numbers depend on the host and mean nothing. An emulator is only
useful to check that the setup works.

Keep the device awake. Android freezes cached background processes. If the screen sleeps during a
long workload, the run stalls at 0% CPU and the next launch cannot be confirmed. The driver app keeps
the screen on and the harness wakes the device. A device that sleeps for another reason, such as low
battery or a device policy, still stalls.

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

A group run shows its progress on the device screen:

- the workload in progress
- whether the iteration is a warmup or a measured one
- the elapsed time
- each finished workload's median, as it finishes

The header names the current phase. After the last workload it reads `reporting` while the JSON is
written, so that stretch does not look like a stall. When the run ends, the screen shows the results
table.

Each finished workload is also written to logcat:

```bash
adb logcat -s BenchmarkDriver
```

A single-workload run (`-e workload <id>`) shows a plain status text instead. Macrobenchmark waits
on that text, and the run must not pay for a UI. The status goes `starting`, `ready`, `done`, or
`failed <exception>`. `ready` means the untimed setup is finished.

The progress screen costs the numbers a little. The elapsed clock ticks once a second, and each
iteration redraws the current row. Both can happen while a workload is measured, so the app's own
numbers include a small amount of UI work. Macrobenchmark drives the single-workload path and never
draws the progress screen. Treat macrobenchmark as the real Android measurement, and the app's group
report as a rough guide.

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

Each class runs one test case per workload in its group, so a failing workload does not stop the
others. Every run compiles the app ahead of time (`CompilationMode.Full()`).

Results are written to
`benchmarks/macro/build/outputs/connected_android_test_additional_output/`, next to the Perfetto
traces.

### Synthea on Android

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest -Pbenchmark.dataset=synthea
```

This one flag does two things. It copies the packaged data into the driver app's assets, and it
tells the run to use that data. Android cannot read the host filesystem, and from API 30 an app
cannot read `/data/local/tmp`. So the corpus is shipped inside the APK. Only the types a workload
queries are included. At `synthea.population=10` that is about 5.7 MB, which compresses to roughly
1 MB of APK.

A Synthea run that cannot find the data fails. The synthetic dataset is never used in its place.
A build without the copied assets stops the run with:

```
IllegalStateException: benchmark.dataset=synthea, but the Synthea data is not readable here: …
```

The driver shows that message in its status view and in logcat. Macrobenchmark stops waiting as
soon as it appears, instead of running to the timeout. Read the message with:

```bash
adb logcat -d -s BenchmarkDriver
```

When a run starts, the driver logs which dataset it loaded. The single-workload path writes no
report, so this log line is its only record of the dataset:

```bash
adb logcat -d -s BenchmarkDriver | grep dataset=
# dataset=synthea population=11 fingerprint=6ae5c742bfc713c6
```

### Check that every metric is non-zero

A macrobenchmark passes whether or not it measured anything. A green run proves nothing on its own.
Check the metrics with:

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

A zero means the trace section never became a closed slice. `atrace` pairs a section's begin and
end per thread. If the measured block resumes on a different thread than it started on, the pair
breaks without any error. The section name still appears in the trace, but nothing matches it. For
that reason the Android span runs on one dedicated thread. See `BenchmarkSpan.android.kt`.

On an emulator, macrobenchmark refuses to run unless you pass:

```bash
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,UNLOCKED,DEBUGGABLE,LOW-BATTERY
```

This flag only lets the run continue. It does not make the numbers meaningful.

### Sweeping every workload in isolation

With a large corpus, the first question is which workloads survive, not only how fast they are.

`scripts/android_benchmark_sweep.py` runs one workload per Gradle invocation, gives each one its own
timeout, classifies the result, and collects all results in one summary:

```bash
scripts/android_benchmark_sweep.py --population 50000
scripts/android_benchmark_sweep.py --population 50000 --triage-only
scripts/android_benchmark_sweep.py --only crud.create_batch --reuse-corpus
scripts/android_benchmark_sweep.py --groups server --server http://localhost:8080/fhir
```

The sweep has two phases:

1. Triage runs every workload once, to find which ones survive.
2. Full reruns only the survivors, with the measured number of iterations.

At 50,000 patients this means you learn what breaks within an hour, not at the end of a day.
`--triage-only` stops after the first phase.

| Flag | Default | Meaning |
|---|---|---|
| `--population` | `50000` | Synthea patients |
| `--profile` | `standard` | Passed to the driver |
| `--timeout` | `30` | Wall-clock minutes per workload |
| `--triage-iterations` | `1` | Iterations in the triage phase |
| `--full-iterations` | `5` | Iterations in the full phase |
| `--only` | all | Workload ids to run instead of the whole catalogue |
| `--groups` | all | Limit the run to `crud`, `search`, `sync` or `server` |
| `--server` | none | Base URL of a FHIR server. The `server` group needs it. |
| `--reuse-corpus` | off | Use the packaged corpus on disk and skip generation |
| `--dry-run` | off | Print the Gradle commands and stop |

Each workload has two timeouts. The outer one is a wall clock that kills the whole process group.
The driver's own wait is shorter, so the test reports which workload stalled before the outer
timeout kills it.

The sweep passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Without that
flag, AGP uninstalls the driver after every workload. The uninstall removes the seeded database and
the cached corpus scan, and each of the 31 workloads starts from an empty database. With the flag,
read-only workloads (`Isolation.NONE`) reuse the database the previous workload left, so the corpus
is inserted once instead of once per workload. On a Galaxy Tab A9 this cut the time between
iterations from 580 s to 70 s.

Every run is classified from its logcat, its Gradle log and its metrics:

| Status | Meaning |
|---|---|
| `ok` | Passed, and the trace section measured something |
| `no-metric` | Passed but measured zero. See [Check that every metric is non-zero](#check-that-every-metric-is-non-zero). |
| `oom` | `OutOfMemoryError`, or the low-memory killer stopped the driver |
| `timeout` | Ran past the clock with no sign of running out of memory |
| `failed` | Anything else. The first exception line is kept. |
| `skipped` | The `server` group when no `--server` was given |

The sweep checks for out of memory before it checks for a timeout. When the driver dies, its status
view never settles and the wait runs until the clock. Reporting a timeout would name the symptom and
hide the cause.

Results are written under `benchmarks/macro/build/sweeps/<timestamp>/`:

- `summary.json` and `summary.txt` at the top
- for each workload and phase, the `benchmarkData.json`, the Gradle log and the logcat

Each row records the driver's `dataset=… population=… fingerprint=…` line, so the summary shows
which corpus was measured. A row is flagged when its workload queries a resource type the corpus
does not contain. Without the flag, an empty result would look like a fast one.

The sweep reads the workload list from the catalogue sources instead of keeping its own copy. It
fails if a group comes back empty, so renaming a workload cannot silently shrink the sweep. The
script's own logic has tests:

```bash
python3 -m unittest discover -s scripts -t scripts
```

## Web

Web needs a Chromium-based browser. Chromium works as Karma's `CHROME_BIN` directly:

```bash
export CHROME_BIN=/Applications/Chromium.app/Contents/MacOS/Chromium
./gradlew :benchmarks:core:wasmJsBrowserTest
./gradlew :benchmarks:core:jsBrowserTest
```

Both tasks take the same `-Pbenchmark.*` flags as desktop, including `-Pbenchmark.dataset=synthea`:

```bash
./gradlew :benchmarks:core:jsBrowserTest -Pbenchmark.profile=smoke -Pbenchmark.dataset=synthea
```

Without flags, a browser run uses the `smoke` profile, 1 warmup and 3 measured iterations. Desktop's
defaults are heavier. A browser over OPFS is by far the slowest target.

The report is written to `benchmarks/core/build/reports/benchmarks/<js|wasmJs>-<timestamp>.json`,
next to desktop's. Each workload is also marked on the browser's performance timeline under its own
id. Read the marks with `performance.getEntriesByType("measure")`.

A browser can read neither `-P` properties nor the filesystem, so the Karma server does both jobs.
`benchmarks/core/karma.config.d/benchmark-server.js` serves the run config and the packaged Synthea
data, and receives the finished report. That is why `-Pbenchmark.data.dir` and
`-Pbenchmark.report.dir` do not apply on web. Both paths are fixed.

## Sync against a real server

The `server` group is the only way to measure upload. `syncUpload` uses `internal` types for patch
generation, patch ordering and bundle generation. A caller outside the KMP engine can only report
failure through it. So a real server is required for upload, not just convenient.

```bash
benchmarks/tools/start-benchmark-server.sh          # HAPI in Docker. Waits until it answers.
./gradlew :benchmarks:core:packageBenchmarkData     # only needed for server.download
benchmarks/tools/populate-benchmark-server.sh       # only needed for server.download

./gradlew :benchmarks:core:desktopTest \
  -Pbenchmark.groups=server \
  -Pbenchmark.server=http://localhost:8080/fhir

benchmarks/tools/stop-benchmark-server.sh
```

### Loading the server

`populate-benchmark-server.sh` only matters for `server.download`. The two upload workloads create
their own patients. The script sends transaction bundles instead of one request per resource. At
50,000 patients that is roughly 840 requests instead of 167,000:

```
Loaded 2,673 resources in 5s (538/s) into http://localhost:8080/fhir
```

Two properties of the corpus make this work:

- `--exporter.fhir.bulk_data=true` makes every reference a literal `Type/id`, so no entry carries a
  `urn:uuid` placeholder that would need rewriting.
- Entries are sent as `PUT` at those ids. The server keeps the corpus's ids, and loading twice
  updates the resources instead of duplicating them.

Synthea writes an organization or practitioner again for every resource that references it. About
two thirds of those two files are repeats. HAPI rejects a transaction bundle that carries the same
id twice, with `HAPI-0535`. The loader drops repeats as it streams. That is why a 1,165-line
`Organization.ndjson` becomes 405 rows.

`-Pbenchmark.server` can point at any reachable FHIR server. The script is a convenience, not a
requirement. Without the flag, the `server` workloads are skipped and the run says so. It does not
pass with nothing measured.

### On a device

The same group runs on Android, through `FhirEngineServerMacrobenchmark`. Two things differ from
desktop.

First, a phone resolves `localhost` to itself. A server on the host must be forwarded:

```bash
adb reverse tcp:8080 tcp:8080
```

The forward belongs to the adb connection, not to the app, so it survives the process restart that
macrobenchmark does between iterations. `scripts/android_benchmark_sweep.py` sets the forward up
itself when `--server` is a loopback URL:

```bash
scripts/android_benchmark_sweep.py --groups server --server http://localhost:8080/fhir
```

Second, when you run Gradle directly, pass the URL to both the build and the instrumentation:

```bash
./gradlew :benchmarks:macro:connectedReleaseAndroidTest \
  -Pbenchmark.server=http://localhost:8080/fhir \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ohs.fhir.engine.benchmark.macro.FhirEngineServerMacrobenchmark
```

Without a URL, an `assumeTrue` skips the class instead of failing it. A sweep that runs every class
therefore does not report the group as broken when the run did not ask for a server.

The driver app declares `INTERNET` and allows cleartext traffic, which a local HAPI needs. The app
is only a measurement driver and is never published. So it allows cleartext for every host, instead
of using a network config that would need editing for each server.

Upload ids include a token that is unique to each process. Macrobenchmark restarts the driver
between iterations, so a counter kept in memory would start again at the same value. Reusing an id
does not fail the run. The server already holds that resource, so the `PUT` becomes an update, and
`server.upload_creates` would measure update cost under a create's name.

| Workload | What it measures |
|---|---|
| `server.upload_creates` | New resources: patch generation, bundling, POST, consolidation |
| `server.upload_updates` | Updates, so the patch generator diffs against a stored resource |
| `server.download` | Download from a real server: paging, parsing, conflict resolution |

These numbers include the server and the network. Compare them only with another run against the
same server on the same machine. Never compare them with the `sync` group, which runs without a
server, and never across machines. Restart the server between runs you want to compare. Uploads
pile up, and a server with a million rows answers differently from an empty one.

## iOS

```bash
./gradlew :benchmarks:core:iosSimulatorArm64Test
./gradlew :benchmarks:core:iosSimulatorArm64Test -Pbenchmark.dataset=synthea
```

The report is written to `benchmarks/core/build/reports/benchmarks/ios-<timestamp>.json`, like
every other platform's. A simulator shares the host filesystem. So the report folder and the Synthea
folder are normal host paths, passed to the harness through `BENCHMARK_REPORT_DIR` and
`BENCHMARK_DATA_DIR`.

A Synthea run on the simulator takes about 20 minutes at `synthea.population=10`. Most of that time
goes into seeding the corpus for the fresh-database CRUD workloads.

Simulator numbers are only a rough guide. A simulator runs on the host CPU and the host disk. Its
numbers show that the KMP engine works on iOS and roughly where the costs are. They do not show what
an iPhone would do. A real device needs the data bundled into the test app, and this harness does
not do that.

## Sample results

##### [SM-X135G, Samsung Galaxy Tab A9](https://www.gsmarena.com/samsung_galaxy_tab_a9-12558.php)

CPU: MediaTek Helio G99, octa-core (2x2.2 GHz Cortex-A76 and 6x2.0 GHz Cortex-A55)

API 36 (Android 16)

Dataset: the android-fhir `bulk_data` corpus. 20,000 patients and 67,959 resources (Patient,
Encounter, Organization, Practitioner). Generated clinical mix off. One measured iteration per
workload.

###### Data Access API results

From the `crud.*` workloads in `benchmarks/core`, driven one workload at a time by
`FhirEngineCrudMacrobenchmark`. The whole-dataset inserts ran through the in-process harness.

| API | Average duration (ms) | Notes |
|:----|----------------------:|-------|
| create (one transaction) | ~5.80 | whole dataset: 6 m 34.5 s for 67,958 resources |
| create (single batch call) | ~8.14 | 20,000 patients in one `create(vararg)`: 2 m 42.8 s |
| create (one call per resource) | ~18.53 | whole dataset: 20 m 59.0 s |
| get | ~6.28 | 500 reads by id |
| update | ~43.66 | 500 updates in place |
| delete | ~10.44 | 500 deletes by id |

###### Search DSL API

From the `search.*` workloads. Each figure is the average of 20 repeats of the query.

| | Population size | Average duration (ms) | Notes |
|--|----------------:|----------------------:|-------|
| patient_by_given_prefix | 20k | ~375.97 | string prefix on given name |
| patient_by_family | 20k | ~87.33 | string match on family name |
| patient_by_gender_token | 20k | ~4,358.92 | token match on gender |
| patient_by_active_token | 20k | ~1.05 | token match on active |
| patient_birthdate_range | 20k | ~3,670.69 | date range |
| patient_sort_given_asc | 20k | ~8,925.91 | sorted ascending by given name |
| patient_sort_given_desc | 20k | ~9,152.65 | sorted descending by given name |
| patient_paged | 20k | ~839.04 | paged result set |
| patient_by_organization_reference | 20k | ~0.98 | reference to organization |
| observation_by_code | 20k | ~1.83 | token match on observation code * |
| observation_by_value_quantity | 20k | ~1.71 | quantity comparison * |
| patient_two_filters_and | 20k | ~790.30 | two filters, AND |
| patient_revinclude_observation | 20k | ~8,747.96 | reverse include of observations * |
| patient_include_organization | 20k | ~8,921.67 | forward include of organizations |
| patient_has_condition | 20k | ~1.63 | chained `_has` on condition * |
| x_fhir_query_string | 20k | ~53.46 | raw x-fhir-query string |
| patient_count | 20k | ~2.75 | count only |
| patient_given_or_birthdate | 20k | ~966.65 | string OR date |
| patient_given_disjunct_values | 20k | ~813.55 | disjunct values, OR |
| encounter_by_last_updated | 20k | ~295.89 | sorted by `_lastUpdated` |
| risk_assessment_by_probability | 20k | ~10.70 | number comparison † |
| risk_assessment_probability_or_status | 20k | ~6.66 | number OR token † |

*\* The dataset has no Observation or Condition resources, so these queries run against empty tables.*  
*† Measured against 200 seeded RiskAssessments.*

###### Sync API results

Against a local HAPI holding 37,884 resources, reached over `adb reverse`.

| Phase | Duration | Resources | Notes |
|:------|---------:|----------:|-------|
| Download from server | 5 m 14.0 s | 20,000 | all Patients from HAPI at `_count=100` |
| Upload (creates, transaction bundle) | 0.66 s | 100 | bundled `PUT` |
| Upload (updates, transaction bundle) | 0.71 s | 100 | bundled `PATCH` |
| In-process download, empty database | 21 m 59.8 s | 67,958 | conflict detection, indexing, write |
| In-process download over local edits | 35 m 41.7 s | 67,958 | conflict resolver runs on half the patients |

## Reading the results

Every report records the platform and a dataset `fingerprint`. Two reports are comparable only if
both values match. A different platform, seed, population or dataset kind means the numbers measure
different things.

For each workload the report has the raw `samplesMillis`, plus `min`, `median`, `p90`, `max`,
`mean`, `stdDev` and `medianMillisPerOp`. Use the median.

`p90` is null unless at least 10 iterations were measured. The default is 5. With fewer than ten
samples, no sample falls in the top tenth, so an interpolated p90 is just the maximum under another
name. For a p90, pass `-Pbenchmark.iterations=10` or more. To judge how noisy a shorter run was, use
`stdDev`, the sample standard deviation (divided by n-1).

### Sanity checks

Before you trust a run:

- The search medians must spread out. If they are all similar and fast, the page cache was never
  disturbed and the search numbers mean nothing. On desktop at `standard`, they range from roughly
  0.6 ms to 108 ms.
- Every macrobenchmark `TraceSectionMetric` must be non-zero. A zero means the section never reached
  the trace. The usual causes are a debuggable build, a missing `<profileable>`, or a workload id
  that does not match the span name. Nothing reports this failure.
- `isolationNote` must be null. When it is set, the platform could not give the workload the
  isolation it asked for, and the number is weaker than it looks.
- Run the same profile twice. The change between the two medians should be well under the threshold
  you want to gate on. Macrobenchmark's own variance is roughly 5–10%.

## Known limitations

- A macrobenchmark passes whether or not it measured anything. A run whose trace sections are all
  zero does not fail, so check the metrics, not the exit code. See
  [Check that every metric is non-zero](#check-that-every-metric-is-non-zero).
- Web cannot reset the database in-process. Closing the database breaks the SQLite Web Worker. Not
  closing it leaves the worker holding the exclusive OPFS handle, so a reopen never completes.
  `FRESH_DATABASE` therefore falls back to `CLEAR_TABLES` on web, and the report records that. A
  truly cold web measurement needs a page reload.
- Upload sync needs a real server. `syncUpload` expects response mapping types that are `internal`,
  so a caller outside the KMP engine can only report failure. The `server` group measures upload
  against a running FHIR server. There is no in-process equivalent.
- `js` and `wasmJs` have two existing `FhirEngineImplTest` failures that are unrelated to
  benchmarking.
- A cold cache is only as cold as SQLite's cache. `-Pbenchmark.coldcache` reopens the database. That
  drops SQLite's page cache but not the operating system's file cache. So the read is colder than
  the default, but not truly cold. Web cannot reopen in-process at all, and the report says so. On
  desktop at 20,000 patients, the flag moved search medians by under 6%, which is inside the noise.
  The file cache stays warm and the working set is small. The flag is likely to matter more on a
  device with real storage.

## Micro benchmarks

The micro benchmarks in `:engine` measure pure-CPU functions and SQLite. What they found is in
[benchmark-results.md](benchmark-results.md).

### Running

```bash
./gradlew :engine:prBenchmark :engine:prNoisyBenchmark   # what CI runs on a pull request; smallest sizes
./gradlew :engine:indexBenchmark   # the sweeps at full size
./gradlew :engine:benchmark        # everything at full size; CI on main or a `benchmark:full` PR
```

Run nothing else on the machine while they run.

Sources are in `engine/src/desktopBenchmark/kotlin/` and can use the engine's `internal`
declarations. They run on the desktop JVM only, under JMH.

| Class                            | What it measures                                                                        |
|----------------------------------|-----------------------------------------------------------------------------------------|
| `ResourceIndexerBenchmark`       | `ResourceIndexer.index()` — a FHIRPath evaluation per search parameter, on every write  |
| `ResourceSerializerBenchmark`    | The serialize/deserialize floor under every read and write                              |
| `JsonDiffBenchmark`              | `JsonDiff.diff()`, run on every update                                                  |
| `UcumCanonicalBenchmark`         | Canonicalizing a quantity, which every indexed value and every filter pays              |
| `SearchQueryBenchmark`           | `Search.getQuery()` and `XFhirQueryTranslator` — per-query cost, independent of how much is stored |
| `SearchExecutionBenchmark`       | A search end to end: the query, the rows, a parse per row, and `_include`/`_revinclude` |
| `SearchResultSizeBenchmark`      | The same, swept by how many resources come back                                         |
| `SortBenchmark`                  | What sorting costs, and whether paging escapes it                                       |
| `DateIndexShapeBenchmark`        | Narrow and wide date range searches under each date index column order                  |
| `StringIndexCollationBenchmark`  | Prefix search under each string index collation, from 1,000 to 50,000 rows              |
| `QuantityIndexShapeBenchmark`    | Quantity search, with and without a unit, under each quantity index column order        |
| `LookupIndexCoveringBenchmark`   | Reference and uri lookups with and without `resourceUuid` in the index                  |
| `TokenIndexShapeBenchmark`       | Token search with and without a system, with and without `index_system` in the index    |
| `ConcurrentAccessBenchmark`      | A read competing with a writer, as during a long sync, under each journal mode          |
| `EngineCreateBenchmark`, `EngineUpdateBenchmark`, `EngineDeleteBenchmark` | Writes through `DatabaseImpl`: one transaction, and a local change per resource |
| `BulkImportBenchmark`            | A download page written in one transaction, with no local change recorded               |
| `ResourceReadBenchmark`          | Reading by id through `ResourceDao`                                                     |
| `SyncDownloadBenchmark`          | Ingesting a download through `syncDownload`, as inserts and as updates, by queue size    |
| `LocalChangeReadBenchmark`       | Reading the pending queue and its references, which is where an upload starts           |
| `UploadAssemblyBenchmark`        | Squashing pending changes into patches, and patches into upload requests                |
| `PatchOrderingBenchmark`         | Tarjan's over the pending-upload graph, the only cost that grows with queue length      |
| `DatabaseOpenBenchmark`          | Opening the database, fresh and seeded                                                  |

The tasks map to configurations in `engine/build.gradle.kts`:

- `main` (`benchmark`): every class, every `@Param` value, five warmups and ten one-second
  iterations.
- `pr` (`prBenchmark`): everything except `SyncDownloadBenchmark`, `ConcurrentAccessBenchmark` and
  the `prNoisy` classes, in three forks of three warmups and five half-second iterations, with the
  sweeps at their smallest size (`rows=1000`, `changeCount=50`, `results=100`) and the index
  benchmarks at the shipped index only (`shape=current`, `collation=nocase`).
- `prNoisy` (`prNoisyBenchmark`): the engine write, `BulkImport`, `ResourceRead` and `DatabaseOpen`
  classes in three forks of five one-second iterations, because one invocation can take about
  300 ms.
- `index` (`indexBenchmark`): the classes with a `@Param` grid or a seeded corpus, at full size,
  three warmups and five half-second iterations.

### Writing a benchmark

- Seed index, sort, search and concurrency benchmarks straight into the index tables with
  `IndexBenchmarkDatabase`, not through `FhirEngine`. FHIRPath indexing would dominate the setup.
- Call `assertSelectivity` in `@Setup`. An index pays only when the predicate rejects most rows, so
  keep a predicate below a few percent of the table.
- When arms differ by index, assert in `@Setup` the plan each arm depends on (`planFor`). An index
  inherits its column's collation, so two arms can otherwise be the same index.
- Use a per-invocation `@Setup` only when one invocation takes milliseconds or more.

### Query plans

`SearchQueryPlanTest` (in `:engine`'s `desktopTest`) runs `EXPLAIN QUERY PLAN` over each search
shape and asserts which SQLite index it uses.

```bash
./gradlew :engine:desktopTest --tests "*SearchQueryPlanTest*"
```

A plan shows a lost index in about a second, on an empty database. A timing run shows it only at a
large corpus, and a warm page cache can hide it. Tests that pin a known shortfall are listed under
[Known shortfalls](benchmark-results.md#known-shortfalls).

`StringSearchMatchingTest` runs real searches against a real database and asserts which rows come
back. `SearchTest` only compares generated SQL, so it cannot catch a collation change that returns
the wrong rows.

### A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 runs JMH with `shouldFailOnError` set to `false`, and has no setting to
change it. A benchmark whose `@Setup` throws prints `<failure>`, is left out of the JSON report, and
the process exits 0. `engine/build.gradle.kts` scans the runner's output and fails the task when a
failure marker appears.

### Comparing runs by hand

A better plan is not always faster; that depends on size and selectivity. JMH forks, warms and
reports a confidence interval itself. For end-to-end comparisons run by hand:

- Interleave the arms: A, B, A, B.
- Run at least three repetitions per arm, and compare the spreads, not only the medians.
- Keep a control measurement the change cannot affect. Its spread is the noise floor.
- Ignore relative deltas below a few milliseconds.

### What CI measures

**On a pull request**, the job runs `:engine:prBenchmark` and `:engine:prNoisyBenchmark` against
the base branch and then the head, on the same runner, and comments with the difference. Scores
from two different jobs are not comparable, because shared runners differ in machine class.

The benchmarks are split across two jobs (`-Pbenchmark.shard=1` or `2`; `firstShard` in
`engine/build.gradle.kts`). Each job runs base and head for its share, so a row is compared on one
runner. The comment workflow merges the reports.

A row is flagged when the difference is outside its 95% interval and is at least 7.5%. The interval
is a pooled t-interval over the fork means: JIT decisions vary between forks more than one fork's
own interval shows. On identical code, one fork per side flagged 3–21 of 75 rows; three forks
flagged one at 5% and none at 7.5%. The comment shows the interval next to the change.

A row whose interval is wider than 7.5% is **too noisy**. Otherwise an unflagged row is
**unchanged**. The comment shows flagged rows and collapses the rest.

If either side has one fork, as when the base predates the forks setting, the comment falls back to
JMH's 99.9% intervals combined in quadrature.

The comment also counts the changed files the benchmarks compile: `commonMain`, `desktopMain`,
`desktopBenchmark` and the build files. With none changed, the benchmarks are skipped, and an
earlier comment says so. A `benchmark:full` pull request always runs.

On a pull request the databases live on tmpfs (`-Pbenchmark.tmpdir=/dev/shm/benchmarks`), because
the runner's disk speed varies within a job. SQL, indexing and cascades still run.

If the base branch has no benchmarks, its run fails, and the comment shows the head alone.

A pull request labelled `benchmark:full` runs `:engine:benchmark` on both sides instead. To apply
it to an open pull request, add the label and re-run the job.

**On a push to `main`**, `:engine:benchmark` runs once, at full size.

Every run writes its table to the run's summary page.

CI runs on the desktop JVM: a good indicator for algorithmic cost, and a poor one for I/O-bound work
on a device.
