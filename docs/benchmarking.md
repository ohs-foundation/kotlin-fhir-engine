# Benchmarking

The micro benchmarks in `:engine` measure pure-CPU functions and SQLite. What they found is in
[benchmark-results.md](benchmark-results.md).

## Running

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
| `DateIndexShapeBenchmark`        | Date range search under each date index column order, from 1,000 to 50,000 rows         |
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
  the `prNoisy` classes, at ten half-second iterations, with the sweeps at their smallest size
  (`rows=1000`, `changeCount=50`, `results=100`).
- `prNoisy` (`prNoisyBenchmark`): the engine write, `BulkImport`, `ResourceRead` and `DatabaseOpen`
  classes at ten one-second iterations, because one invocation can take about 300 ms.
- `index` (`indexBenchmark`): the classes with a `@Param` grid or a seeded corpus, at full size,
  three warmups and five half-second iterations.

## Writing a benchmark

- Seed index, sort, search and concurrency benchmarks straight into the index tables with
  `IndexBenchmarkDatabase`, not through `FhirEngine`. FHIRPath indexing would dominate the setup.
- Call `assertSelectivity` in `@Setup`. An index pays only when the predicate rejects most rows, so
  keep a predicate below a few percent of the table.
- When arms differ by index, assert in `@Setup` the plan each arm depends on (`planFor`). An index
  inherits its column's collation, so two arms can otherwise be the same index.
- Use a per-invocation `@Setup` only when one invocation takes milliseconds or more.

## Query plans

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

## A failing benchmark does not fail the build

kotlinx-benchmark 0.5.0 runs JMH with `shouldFailOnError` set to `false`, and has no setting to
change it. A benchmark whose `@Setup` throws prints `<failure>`, is left out of the JSON report, and
the process exits 0. `engine/build.gradle.kts` scans the runner's output and fails the task when a
failure marker appears.

## Comparing runs by hand

A better plan is not always faster; that depends on size and selectivity. JMH forks, warms and
reports a confidence interval itself. For end-to-end comparisons run by hand:

- Interleave the arms: A, B, A, B.
- Run at least three repetitions per arm, and compare the spreads, not only the medians.
- Keep a control measurement the change cannot affect. Its spread is the noise floor.
- Ignore relative deltas below a few milliseconds.

## What CI measures

**On a pull request**, the job runs `:engine:prBenchmark` and `:engine:prNoisyBenchmark` against
the base branch and then the head, on the same runner, and comments with the difference. Scores
from two different jobs are not comparable, because shared runners differ in machine class.

A row is flagged when the difference is outside its 99.9% confidence interval and is at least 5%.
The interval of a difference combines the two errors in quadrature. A row whose combined error is
wider than 5% says **too noisy**, with the smallest change it could detect. A blank row means no
change of 5% or more.

On a pull request the databases live on tmpfs (`-Pbenchmark.tmpdir=/dev/shm/benchmarks`), because
the runner's disk speed varies within a job. SQL, indexing and cascades still run.

If the base branch has no benchmarks, its run fails, and the comment shows the head alone.

A pull request labelled `benchmark:full` runs `:engine:benchmark` on both sides instead. To apply
it to an open pull request, add the label and re-run the job.

**On a push to `main`**, `:engine:benchmark` runs once, at full size.

Every run writes its table to the run's summary page.

CI runs on the desktop JVM: a good indicator for algorithmic cost, and a poor one for I/O-bound work
on a device.
