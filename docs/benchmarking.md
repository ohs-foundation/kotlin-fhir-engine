# Benchmarking

This covers the micro benchmarks that live in `:engine`. They measure what sits underneath the
engine's public surface — pure-CPU functions, and SQLite itself — where the cost of an algorithm or
an index is the whole story.

```bash
./gradlew :engine:prBenchmark :engine:prNoisyBenchmark   # what CI runs on a pull request
./gradlew :engine:indexBenchmark   # just the index and tuning sweeps, about a minute
./gradlew :engine:benchmark        # everything at full size; what CI runs on a push to main
```

## Micro benchmarks

They live in `:engine` rather than in a module of their own, in a `benchmark` compilation associated
with `main`. That association is the reason for the placement: it grants access to the engine's
`internal` declarations, the same way test compilations do. Sources are in
`engine/src/desktopBenchmark/kotlin/`.

Desktop/JVM only. kotlinx-benchmark backs the JVM target with JMH, which forks, warms and reports a
confidence interval, so these land near ±0.3%. Its Kotlin/JS support targets Node while this
project's web targets are browser-configured, and a native target would need a `macosArm64` the
engine does not build.

| Class                            | What it measures                                                                        |
|----------------------------------|-----------------------------------------------------------------------------------------|
| `ResourceIndexerBenchmark`       | `ResourceIndexer.index()` — a FHIRPath evaluation per search parameter, on every write  |
| `JsonDiffBenchmark`              | `JsonDiff.diff()` — the hand-written RFC 6902 replacement for Jackson + jsonpatch       |
| `ResourceSerializerBenchmark`    | The serialize/deserialize floor under every read and write                              |
| `SearchQueryBenchmark`           | `Search.getQuery()` — per-query cost, independent of how much is stored                 |
| `MoreResourcesBenchmark`         | `getResourceClass`, `updateMeta`, `withId` — per-resource helpers                       |
| `PatchOrderingBenchmark`         | Tarjan's over the pending-upload graph, the only cost that grows with queue length      |
| `DateIndexShapeBenchmark`        | Date index column order, swept from 1,000 to 50,000 rows                                |
| `StringIndexCollationBenchmark`  | String index collation, swept the same way                                              |
| `ResourceInsertBenchmark`, `ResourceUpdateBenchmark`, `ResourceDeleteBenchmark`, `ResourceReadBenchmark` | The CRUD paths through the real `ResourceDao` and schema |
| `PayloadRepresentationBenchmark` | Storing `serializedResource` as JSON text against the same resources as a protobuf blob |
| `SqliteTuningBenchmark`          | `ANALYZE`, `journal_mode` and `synchronous`, which the engine never sets                |

The index sweeps write rows straight into the index tables rather than through `FhirEngine`, because
the question is what an index costs, not what indexing costs. That is also what makes 50,000 rows
affordable: the engine path spends about 200 us of FHIRPath per resource and would turn seconds of
setup into twenty minutes of it.

Every index trial asserts its query still selects the slice it was designed to — see
[Selectivity decides everything](#selectivity-decides-everything) — and the collation sweep also
asserts its two arms produce *different* query plans, because an index created over a NOCASE column
is NOCASE whatever the arm intended, and two identical arms compare cheerfully to zero difference.

### What the sweeps say today

`StringIndexCollationBenchmark`, us/op: binary 65.7, 534.2, 3139.9 at 1k/10k/50k rows against nocase
22.8, 31.9, 78.9 — the first scales with the table and the second barely moves, which is the whole
difference between a scan and a seek. **39.8x at 50,000 rows.**

`DateIndexShapeBenchmark`: a flat 11-13% for the reordered index at every size, against a selective
window. Not enough to pay for the extra index and the write cost it brings; see
[The two remaining shortfalls](#the-two-remaining-shortfalls).

`SqliteTuningBenchmark`: **nothing here is worth adopting**, and this is now a settled answer rather
than an absence of one. On an idle machine all four arms agree to within 1% on both write shapes —
a batched insert is 5.61 ms against 5.67 for WAL, and one transaction per row is 2.163 ms against
2.172 — with the `analyze` arm, which cannot affect a write at all, differing from `default` by
0.16%. That is the noise floor, and it is far below any difference between the arms. `ANALYZE` does
nothing for the prefix search either (45.0 against 46.3 us/op).

One caveat that does not transfer: this is macOS, where SQLite's default `fsync` does not force a
full disk barrier. Journal mode is almost entirely about what a commit must durably record, so a
platform with stricter durability — Android on real storage — could answer differently. The
conclusion here is "no effect on desktop", not "no effect".

`PayloadRepresentationBenchmark`: a binary payload is **half the bytes and about 14% of the read**.
Storing 20,000 mixed resources takes 7.19 MB as JSON text against 3.51 MB as a protobuf blob, and
fetching two hundred of them is 125 us against 44 — a 65% saving on the I/O.

The reason the end-to-end number is so much smaller than the size reduction is that **parsing
dominates and does not change**: decoding those payloads costs 443 us as JSON and 435 us as
protobuf, a difference inside the error bars. The two halves add up almost exactly — 125 + 443
against a measured 565, and 44 + 435 against a measured 485 — which is a useful check that the
benchmark is measuring what it claims.

So the trade is roughly 14% on reads that touch many rows, 10% on a point read, 14% on a write, and
half the disk, against a destructive schema migration and a wire format that is not self-describing.
Worth having the number before anyone argues it either way.

## Index usage

`SearchQueryPlanTest` (in `:engine`'s `desktopTest`) runs `EXPLAIN QUERY PLAN` over each search shape
and asserts which SQLite index it uses.

```bash
./gradlew :engine:desktopTest --tests "*SearchQueryPlanTest*"
```

Not a benchmark, deliberately. A lost index only becomes visible in a timing run at a large corpus,
and a warm page cache hides it even then. The plan reports it in about a second, from an empty
database. Timing answers *how slow*; the plan answers *why*.

| Search                  | Index columns narrowed             | Covering |
|-------------------------|------------------------------------|----------|
| token                   | all three                          | yes      |
| reference, uri          | all three                          | no       |
| number, quantity        | all three, range on the value      | no       |
| string prefix, contains | all three, as a range              | no       |
| **string `:exact`**     | **two — `index_value` unused**     | no       |
| **date, dateTime**      | **two — the range columns unused** | yes      |

Sorting is never index-backed: every sorted search builds two temporary B-trees.

`StringSearchMatchingTest`, alongside it, runs real searches against a real database and asserts
which rows come back. `SearchTest` only compares generated SQL, so without it the collation could be
changed in either direction and every test would still pass while search quietly returned the wrong
rows.

### The two remaining shortfalls

Both are pinned as tests that name them.

**`:exact` string search** compares `COLLATE BINARY` against a NOCASE index, and SQLite will not use
an index whose collation differs from the comparison's. This is the deliberate price of making the
common prefix search fast: the two cannot both be indexed without a second, BINARY-collated column
mirroring `index_value`, which is disk and write cost for the rarer path.

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
A range predicate can only use the column immediately after the equality prefix, and `resourceUuid`
sits in between, so neither comparator family can range. Moving it last and adding a second index
leading with `index_to` makes both usable, and measured **worse** end to end — about 13% on both a
birth-date range search and a delete, interleaved, n=3. `DateIndexShapeBenchmark` sweeps the same
change from 1,000 to 50,000 rows against a *selective* window and finds a flat ~13% gain instead.
The two do not contradict each other — they are different selectivities. Left as it is until
something measures a case that wants it.

### Selectivity decides everything

An index can only pay for itself when the predicate rejects most rows. That makes selectivity a
property of the **dataset**, not only the query, and getting it wrong disables a whole suite
silently.

This was learned the hard way, on the end-to-end benchmark suite that lives outside this module. It
ran for a long time against a generated corpus of eight given names and eight family names, so
`family = "Smith"` matched one patient in eight, and a birth-date range asked for thirty years of a
seventy-year spread — over 40%. At those fractions no index can help, so its search workloads could
not tell a working index from a missing one. The prefix-search fix above measured as *no change at
all* there and was nearly discarded on the strength of it. Widening the corpus to 676 distinct names
made the same change 3.4x to 4.5x end to end, against the 38x measured here in isolation.

This is why every index benchmark here calls `assertSelectivity` in its setup: a trial whose
predicate stops matching the slice it was designed for fails loudly instead of quietly measuring row
fetching. If you add one, check what fraction of the table it matches. Anything above a few percent
is not measuring indexing.

### Reading these plans honestly

A query plan tells you *why* something is slow. It does not establish that a better-looking plan is
faster; that depends on size and selectivity, and here it twice was not.

Benchmarks on a developer machine need care to mean anything. During this work several single-run
comparisons produced double-digit "effects" that vanished under repetition — including one on a
read-only workload that no index change could possibly touch. What worked:

- **Interleave the arms.** Run A, B, A, B, not all of A then all of B; machine state drifts.
- **At least three repetitions per arm**, and compare the spread, not just the medians. Overlapping
  ranges are not a result.
- **Keep a control group** — measurements the change cannot affect. Their spread is the noise floor.
- Ignore relative deltas on anything below a few milliseconds.
- Run nothing else on the machine while a sweep is going. A Gradle build counts. So does a rebase.

The JMH benchmarks here need none of this discipline themselves: they fork, warm and report a
confidence interval. The discipline is for end-to-end comparisons run by hand.

## A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 builds its JMH `Runner` with `shouldFailOnError` left at JMH's default of
`false`, and exposes no setting to change it. A benchmark whose `@Setup` throws is therefore
reported as `<failure>` in the console, **omitted entirely from the JSON report**, and the process
still exits 0. The report has no failure or error field, so a run that lost three of twenty
benchmarks is indistinguishable from one that was only ever configured to run seventeen.

This is not hypothetical. `StringIndexCollationBenchmark` asserts that its two arms produce
different query plans; run it against an engine without the NOCASE column and three of its six
combinations abort in setup. Gradle reported `BUILD SUCCESSFUL`.

`engine/build.gradle.kts` therefore watches the runner's own output and fails the task when a
failure marker appears.

That check is what the CI job stands on. Without it, a run that lost three of twenty benchmarks
would report exactly the same green tick as a clean one.

## What CI measures, and how it compares

Two tiers, chosen by what triggered the run.

**On a pull request, `:engine:prBenchmark` and `:engine:prNoisyBenchmark`, twice.** The job checks
out the base branch beside the head and runs the same tier against each, on the same runner,
minutes apart. The comment on the pull request is the *difference* between the two.

The tier is split in two so that each benchmark gets the sampling it needs to show a change.
`prBenchmark` runs everything else except `SqliteTuningBenchmark` — journal and fsync settings mean
nothing on tmpfs, and its question is settled — at ten half-second iterations, with the scaling sweeps pinned to
their smallest size (`rows=1000`, `changeCount=50`). `prNoisyBenchmark` runs the CRUD and
`MoreResources` benchmarks at ten one-second iterations instead: an indexed update takes about
300 ms on a runner, so a half-second iteration would hold a single disk write.

That pairing is the whole point. Two scores from two CI jobs cannot be compared — shared runners
differ in machine class between jobs, and this suite has produced double-digit phantom "effects"
from exactly that. Two scores from one machine a few minutes apart can be, and their errors say by
how much. A row is flagged only when the difference lies outside its own 99.9% confidence interval
*and* is at least 5%: a tight-but-tiny shift and a large-but-noisy one are each left alone. The
interval of a difference combines the two errors in quadrature; the common shortcut of requiring the
two intervals not to overlap adds them instead, and is conservative enough that it left six more
benchmarks unable to see a 5% change. A flag is a prompt to look, not a verdict — each side is
still a single run.

A row that is not flagged is not necessarily unchanged. When the two errors together are wider than
5% of the score, a 5% regression could not have been told apart from noise, and the row says
**too noisy** with the smallest change it could have caught. Read a blank as "no change of 5% or
more", and a too-noisy row as "no information".

Iteration count matters more than it looks. JMH's interval scales with Student's t, which at five
iterations is 8.47 and at ten is 4.78, so doubling the iterations shrinks every interval to about
40% of its width rather than by the square root alone. On the first CI run, at five iterations, 29
of the 54 pull-request benchmarks could not show a 5% change. Projected from those same runs, ten
iterations leaves about 13 — mostly the disk-bound CRUD and SQLite-tuning rows, which is the honest
limit of a shared runner.

On a pull request the benchmark databases live on tmpfs (`-Pbenchmark.tmpdir=/dev/shm/benchmarks`):
the runner's disk varied up to 3x within one job, which would drown out any code change. Write
benchmarks still run their SQL, indexing and cascades; only disk speed is removed.

If the base branch predates the benchmarks, its run fails, that failure is tolerated, and the
comment shows the head on its own with the "indicative only" caveat.

**On a push to `main`, `:engine:benchmark`, once.** The full tier, scaling sweeps included. Its
artifact is the record a later trend would be built from; nothing consumes it yet.

Locally, the same tasks: `prBenchmark` and `prNoisyBenchmark` for a quick check, `indexBenchmark`
for the sweeps, `benchmark` for everything. Run nothing else while they run — a Gradle build in another window is
enough to widen every error bar.

What CI establishes, then: that the benchmarks **run and their assertions hold**, and on a pull
request whether the head is measurably slower than its base *on that runner*. What it still does
not establish is anything about a device: this is desktop JVM, a good indicator for algorithmic
cost and a poor one for anything I/O-bound. See
[Reading these plans honestly](#reading-these-plans-honestly).
