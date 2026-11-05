# Benchmark results

How fast the KMP engine is, measured by the micro benchmarks in `:engine` on CI, and the design
decisions those measurements settled. How to run them is in [benchmarking.md](benchmarking.md).

## Summary

| Operation | Time |
|---|---:|
| Read one patient by id | 38 µs |
| Search patients by exact name, 1,000 patients | 52 µs |
| Search observations by code, 1,000 observations | 93 µs |
| Search patients by name prefix, 1,000 patients | 117 µs |
| Return an unfiltered first page of 50, 50,000 patients | 46 µs |
| Create a patient, indexing and local change included | 327 µs |
| Update a patient | 389 µs |
| Delete a patient | 201 µs |
| Index a patient's search parameters (FHIRPath, CPU only) | 164–173 µs |
| Parse a stored patient | 1.6–4.2 µs |
| Build a search query with five filters | 6.3 µs |
| Download and store 1,000 new patients in ten pages | 532 ms |
| Order 500 pending changes for upload | 204 µs |
| Open a database, first launch / later launches | 1.28 ms / 0.38 ms |

Per-resource figures are measured in batches of fifty and divided.

## Environment and method

| | |
|---|---|
| Run | [CI run 38826212045](https://github.com/ohs-foundation/kotlin-fhir-engine/actions/runs/38826212045), `:engine:benchmark` at commit `4416164d`, 15 October 2026 |
| Runner | GitHub-hosted `ubuntu-latest`, image `ubuntu24` 20261012.334.1 |
| CPU | AMD EPYC 9V74, 4 vCPUs (2 cores, 2 threads each), 32 MiB L3 |
| Memory | 15 GiB |
| OS | Ubuntu 24.04.5 LTS, kernel 6.17 (Azure) |
| JVM | OpenJDK 21.0.12.1, 64-bit Server VM |
| JMH | 1 fork, 5 warmup and 10 measured iterations of 1 s each |
| Storage | Databases on tmpfs (RAM), so disk speed is not in these numbers |

This is a shared server, not a phone. The numbers show algorithmic cost and how it scales. A device
will be slower, most of all on disk. Compare numbers only within one run: each `±` is the 99.9%
confidence interval, and another runner can differ by more.

The timings predate schema version 12 and the search and sync changes recorded under
[Design decisions](#design-decisions). Rows those changes affect most are noted under their table.
Re-run the CI benchmark to refresh them.

## Search

### Index use per search shape

From `SearchQueryPlanTest`, on schema version 12.

| Search                  | Index columns narrowed             | Covering |
|-------------------------|------------------------------------|----------|
| token, code only        | all three                          | yes      |
| token with a system     | all three, `index_system` read from the index | yes |
| reference, uri          | all three                          | yes      |
| number                  | all three, range on the value      | no       |
| quantity, no unit       | all three, range on the value      | no       |
| quantity with a unit    | all four: the unit, then the range on the value | no |
| string prefix           | all three, range on the value      | no       |
| string `:exact`         | all three: seeks NOCASE, then keeps the exact case | no |
| string `:contains`      | two, a leading wildcard cannot seek | no     |
| date, dateTime          | all three, range on `index_from` or `index_to` | yes |
| `_revinclude`           | all three, then the resource by uuid | no     |
| `_include`              | two on the references, then the resource by type and id | no |

### Search kinds

Every search but `_include` costs under 500 µs at 5,000 patients. `SearchExecutionBenchmark`, µs
per search, with as many observations as patients:

| Search | 1,000 patients | 5,000 patients |
|---|---:|---:|
| string `:exact` | 52 | 51 |
| reference | 59 | 58 |
| token | 93 | 288 |
| number range | 96 | 308 |
| count | 101 | 326 |
| string prefix | 117 | 371 |
| string `:contains` | 118 | 374 |
| first page of fifty, unfiltered | 246 | 242 |
| `_revinclude` | 179 | 461 |
| **`_include`** | **2,161** | **46,660** |

The `_include` row was measured before the [include join](#include-join) change.

### Result size

A plain page costs about 4 µs per resource returned. `SearchResultSizeBenchmark`, µs, over 10,000
patients, varying only how many come back:

| Returned | page | with `_include` | with `_revinclude` |
|---:|---:|---:|---:|
| 1 | 55 | 104 | 103 |
| 100 | 423 | 5,681 | 1,507 |
| 1,000 | 3,731 | 23,820 | 64,760 |
| 10,000 | 39,110 | 819,900 | **5,984,000** |

The include columns were measured before the [include results](#include-results-grouping) change.

### Sorting

Sorting a filtered slice costs almost nothing. `SortBenchmark`, µs:

| | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| unsorted first page | 46 | 46 | 46 |
| sorted first page | 658 | 6,497 | **36,720** |
| unsorted, all rows | 125 | 991 | 7,026 |
| sorted, all rows | 711 | 8,134 | 52,470 |
| filtered to one name prefix, unsorted | 101 | 605 | 3,731 |
| filtered to one name prefix, sorted | 102 | 638 | 3,921 |

The sorted first page was measured before the [unfiltered sort](#unfiltered-sort-reads-the-index-in-order)
change, which brings it under 100 µs in a local run.

## Writes and sync

| Operation | Time |
|---|---:|
| Create 50 patients through the engine | 16.4 ms ±6.6% |
| Update 50 patients | 19.4 ms ±5.2% |
| Delete 50 patients | 10.0 ms ±3.2% |
| Import a download page of 500, no local changes recorded | 185 ms ±7.3% |
| Read 50 pending changes / 500 | 96 µs / 613 µs |
| Squash 50 / 500 resources' changes into one patch each | 344 µs / 3,441 µs |
| Build upload requests for 50 / 500 resources | 123 µs / 1,191 µs |

`SyncDownloadBenchmark`, 1,000 patients in ten pages, ms:

| Pending local changes | 0 | 100 | 1,000 |
|---|---:|---:|---:|
| new patients (`insert`) | 532 | 526 | 554 |
| patients already stored (`update`) | 789 | 781 | 838 |

Measured before the [conflict detection](#conflict-detection-by-type-and-id) change.

### Reads during a sync

Under WAL a read during a write pays 49%. Under the rollback journal it waits. The KMP engine opens
in WAL, and `JournalModeTest` pins it. `ConcurrentAccessBenchmark`, a prefix search over 20,000
patients, alone and while another thread commits small transactions, µs:

| Journal | alone | with a writer |
|---|---:|---:|
| WAL (shipped) | 1,189 | 1,767 |
| rollback (`delete`) | 1,173 | 8,651 ±16% |

Under the rollback journal a writer holds an exclusive lock for its transaction and readers wait:
7x slower here, on tmpfs, and more on real storage.

## Known limitations

Each is pinned by a test in `SearchQueryPlanTest`, except where noted.

**String `:contains`** compiles to a pattern with a leading wildcard, which no index can seek
whatever the collation.

**A filtered sort** sorts its matches in a temporary b-tree. A weak filter, one that matches most
resources, still sorts every match.

**A deep page of an unfiltered sort** walks every value before it, and a page among the resources
with no value first counts those that have one. In a local prototype at 50,000 patients the last
page took 153 ms against the single query's 60 ms. Measured, not pinned.

**`_include`** narrows its reference rows on `(resourceType, index_name)` only. SQLite prefers that
index to the one on `resourceUuid`, and forcing the other costs more when a search includes many
base resources.

## Design decisions

Each entry records the problem, the choice, and the benchmark that settled it. Times are µs per
search at 1,000 / 10,000 / 50,000 rows unless stated.

### Prefix search collation

**Context.** A prefix search compiles to `LIKE`. SQLite seeks an index for `LIKE` only when the
pattern is a literal or a plain parameter, and only when the index collation matches the
comparison's.

**Decision.** Bind the pattern whole, `LIKE ?` with `x%`, and declare `StringIndexEntity.index_value`
NOCASE. `:exact` seeks with a NOCASE comparison and keeps the exact case with a BINARY one.

**Evidence.** 32x faster at 50,000 rows.

| `StringIndexCollationBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| `binary` | 98 | 595 | 3,769 |
| `nocase` (shipped) | 57 | 67 | 118 |

**Cost.** `=` and `ORDER BY` on the column ignore case unless they say otherwise. The migration
rebuilds `StringIndexEntity`.

### Include join

**Context.** The join compared `re.resourceType||'/'||re.resourceId` with `rie.index_value`. SQLite
cannot seek on an expression, so the work was the product of the two tables: 23x a token search at
1,000 patients and 162x at 5,000 (see [Search kinds](#search-kinds)).

**Decision.** Split `rie.index_value` at the slash and compare each half with a column, so `re`
seeks the unique index on `(resourceType, resourceId)`.

**Evidence.** `SearchQueryPlanTest` pins the seek, and `IncludeSearchMatchingTest` checks the rows
that come back. The reference side still narrows on two columns, as noted in
[Known limitations](#known-limitations).

### Include results grouping

**Context.** `Search.execute` scanned the whole resolved list once per base resource, so the cost
grew with the product of the page and the included resources. A `_revinclude` over 10,000 took
6.0 s (see [Result size](#result-size)).

**Decision.** Group the resolved resources once, by key, which makes the cost linear.

### Unfiltered sort reads the index in order

**Context.** Rows did not arrive in order, so `LIMIT` could not stop early, and a sorted first page
ordered the whole corpus: 795x an unsorted page at 50,000 (see [Sorting](#sorting)).

**Decision.** Run an unfiltered sorted search as segments read in index order: resources with a
value, walked through the sort index, then those without one. The limit stops the walk early. A
filtered sort keeps the single query, because a filter usually leaves few rows to sort.

**Evidence.** In a local run, not CI:

| `SortBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| `sortedFirstPage`, single query | 563 | 5,964 | 33,641 |
| `walkedFirstPage` (shipped) | 60 | 73 | 84 |
| `unsortedFirstPage` | 24 | 24 | 24 |

**Cost.** Deep pages, as noted in [Known limitations](#known-limitations).

### Missing string values sort last

**Context.** The numeric default for a missing value sorted before every string, so a resource with
no value came first in an ascending string sort.

**Decision.** Default to a BLOB, which SQLite orders after every string. An `IS NULL` term also
works but adds a second sort key, which made an unfiltered or broad sort 8–17% slower.

### Quantity index order

**Context.** A search naming a unit compares the unit for equality and then ranges on the value.
One without a unit only ranges on the value. An index serves a range only on the column straight
after its equality prefix, so neither order serves both.

**Decision.** Keep both orders, and SQLite picks per query.

| `QuantityIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| with a unit, `valueFirst` | 50 | 161 | 773 |
| with a unit, `codeFirst` | 44 | 105 | 506 |
| with a unit, both (shipped) | 44 | 105 | 508 |
| without a unit, `valueFirst` | 70 | 434 | 2,305 |
| without a unit, `codeFirst` | 136 | 1,222 | 7,653 |
| without a unit, both (shipped) | 71 | 444 | 2,299 |

**Cost.** One more index on `QuantityIndexEntity`.

### Covering reference and uri indexes

**Context.** Every filter subquery selects only `resourceUuid`, and the reference and uri indexes did
not end in it, so each match cost a row fetch. Reference lookups also carry chained, `_has` and
`_revinclude` searches.

**Decision.** End both indexes in `resourceUuid`.

| `LookupIndexCoveringBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| reference, `notCovering` | 46 | 133 | 2,611 |
| reference, covering (shipped) | 43 | 106 | 1,624 |
| uri, `notCovering` | 45 | 134 | 2,617 |
| uri, covering (shipped) | 44 | 108 | 1,682 |

### Token index carries the system

**Context.** A token search with a system adds `IFNULL(index_system,'') = ?`, and the token index did
not carry `index_system`, so each match cost a row fetch.

**Decision.** Add `index_system` before `resourceUuid`. 37% faster at 50,000 rows, and a code-only
search is unchanged.

| `TokenIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| code only, `noSystem` | 44 | 106 | 1,746 |
| code only, with system (shipped) | 43 | 108 | 1,770 |
| system and code, `noSystem` | 46 | 134 | 2,701 |
| system and code, with system (shipped) | 45 | 116 | 1,694 |

### Date index order

**Context.** The date and dateTime indexes were `(resourceType, index_name, resourceUuid,
index_from, index_to)`. `resourceUuid` sat between the equality prefix and the range columns, so no
range could use the index.

**Decision.** Move `resourceUuid` last and add a second index led by `index_to`, since `<`, `<=`
and `sa` range over `index_from` and `>`, `>=` and `eb` over `index_to`. A filtered date sort looks
dates up by `resourceUuid` through a separate index and is unaffected. An unfiltered one walks the
new index in `index_from` order.

**Evidence.** The narrow window matches about 4% of rows and the wide one about 40%.

| `DateIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| narrow, `uuidFirst` | 336 | 3,386 | 19,800 |
| narrow, range first (shipped) | 276 | 2,949 | 17,280 |
| wide, `uuidFirst` | 455 | 4,806 | 28,560 |
| wide, range first (shipped) | 429 | 4,485 | 27,030 |

### Conflict detection by type and id

**Context.** `syncDownload` read the whole pending queue once per page, payloads included, only to
intersect ids with the page, so a download of new data slowed as the queue grew: 4% at 1,000
pending changes (see [Writes and sync](#writes-and-sync)). It also matched on id only: a downloaded
Patient that shared an id with a pending Observation change counted as a conflict, and resolving it
threw `ResourceNotFoundException`.

**Decision.** Ask the database which of the page's resources, by type and id, have a pending change.
`SyncDownloadConflictTest` pins the type check.

### Earlier questions

Answered by benchmarks that have since been removed. The code is in git history.

- **SQLite tuning.** `ANALYZE` and `synchronous` made no difference outside the noise.
- **Batch size.** Bigger transactions are faster, and the gain flattens after about a thousand
  resources. `DatabaseImpl` writes a batch in one transaction.
- **Protobuf payloads.** Half the disk and somewhat faster reads, against a destructive schema
  migration and a wire format that is not self-describing. Not adopted.
- **Network path.** Requesting and parsing a sync page costs well under a millisecond on the client.
  The time is in the network and the ingest.
