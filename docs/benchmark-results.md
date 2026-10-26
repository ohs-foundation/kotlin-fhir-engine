# Benchmark results

How the engine performs, measured by the micro benchmarks in `:engine` on CI. How to run them is in
[benchmarking.md](benchmarking.md).

## Machine

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

This is a shared server, not a phone. The numbers show algorithmic cost and how it scales; a device
will be slower, most of all on disk. The schema is the one before #109. Compare numbers only within
one run: each `±` below is the 99.9% confidence interval, and another runner can differ by more.

## At a glance

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

## Search

### Index use per search shape

From `SearchQueryPlanTest`, on the same schema.

| Search                  | Index columns narrowed             | Covering |
|-------------------------|------------------------------------|----------|
| token, code only        | all three                          | yes      |
| token with a system     | all three                          | no       |
| reference, uri          | all three                          | no       |
| number                  | all three, range on the value      | no       |
| quantity, no unit       | all three, range on the value      | no       |
| **quantity with a unit**| **two and the range — `index_code` unused** | no |
| string `:exact`         | all three                          | no       |
| string prefix, contains | two — `index_value` unused         | no       |
| date, dateTime          | two — the range columns unused     | yes      |
| `_revinclude`           | all three, then the resource by uuid | no     |
| **`_include`**          | **two — the join compares a concatenation, so neither side seeks** | no |

### Search kinds

`SearchExecutionBenchmark`, µs per search, with as many observations as patients:

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

The searches that seek stay flat as the corpus grows; the ones that narrow on two index columns
grow with it. `_include` costs 23x a plain token search at 1,000 and 162x at 5,000.

### Result size

`SearchResultSizeBenchmark`, µs, over 10,000 patients, varying only how many come back:

| Returned | page | with `_include` | with `_revinclude` |
|---:|---:|---:|---:|
| 1 | 55 | 104 | 103 |
| 100 | 423 | 5,681 | 1,507 |
| 1,000 | 3,731 | 23,820 | 64,760 |
| 10,000 | 39,110 | 819,900 | **5,984,000** |

A plain page costs about 4 µs per resource returned. A `_revinclude` over 10,000 takes 6.0 s.
`Search.execute` rescans the whole resolved list once per base resource, so the include columns
grow with the product of the page and the included resources. Grouping the resolved resources once,
by key, makes them linear and needs no schema change.

### Sorting

`SortBenchmark`, µs:

| | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| unsorted first page | 46 | 46 | 46 |
| sorted first page | 658 | 6,497 | **36,720** |
| unsorted, all rows | 125 | 991 | 7,026 |
| sorted, all rows | 711 | 8,134 | 52,470 |
| filtered to one name prefix, unsorted | 101 | 605 | 3,731 |
| filtered to one name prefix, sorted | 102 | 638 | 3,921 |

`count`/`from` does not bound the work on a sorted search. Rows do not arrive in order, so `LIMIT`
cannot stop early, and a sorted first page orders the whole corpus: 795x an unsorted page at 50,000.
Every page pays this. Sorting a filtered slice costs almost nothing. Avoid a sorted search with no
filter or a weak one, such as all patients in alphabetical order.

## Known shortfalls

Each is pinned by a test in `SearchQueryPlanTest`. Times are µs per search at 1,000 / 10,000 /
50,000 rows.

**Prefix string search** compiles to `index_value LIKE ? || '%' COLLATE NOCASE` and narrows on
`(resourceType, index_name)` only. SQLite applies its LIKE optimisation only to a literal or plain
parameter pattern, and uses an index only when its collation matches the comparison's; both must be
fixed. Fixing them costs `:exact` its index unless a second column mirrors `index_value`, and needs
a schema version bump.

| `StringIndexCollationBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| `binary` (shipped) | 98 | 595 | 3,769 |
| `nocase` | 57 | 67 | 118 |

**`_include`** joins on `re.resourceType||'/'||re.resourceId = rie.index_value`. SQLite cannot seek
on an expression, so neither side uses its index and the work is the product of the two tables (see
[Search kinds](#search-kinds)). `_revinclude` binds `type/id` strings built in Kotlin and seeks;
doing the same for `_include` needs no schema change.

**Quantity with a unit**: the index is `(resourceType, index_name, index_value, index_code)`. The
range on `index_value` comes before `index_code`, so the unit is not used. Putting the unit first
helps a search with a unit and hurts one without; keeping both indices takes the gain without the
loss.

| `QuantityIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| with a unit, `current` | 50 | 161 | 773 |
| with a unit, `codeFirst` | 44 | 105 | 506 |
| with a unit, `both` | 44 | 105 | 508 |
| without a unit, `current` | 70 | 434 | 2,305 |
| without a unit, `codeFirst` | 136 | 1,222 | 7,653 |
| without a unit, `both` | 71 | 444 | 2,299 |

**Reference and uri lookups are not covering.** Every filter subquery selects only `resourceUuid`;
the token index ends in that column and the reference and uri indices do not. Reference lookups
also carry chained, `_has` and `_revinclude` searches.

| `LookupIndexCoveringBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| reference, `current` | 46 | 133 | 2,611 |
| reference, `covering` | 43 | 106 | 1,624 |
| uri, `current` | 45 | 134 | 2,617 |
| uri, `covering` | 44 | 108 | 1,682 |

**Token search with a system** adds `IFNULL(index_system,'') = ?`, and the token index does not
carry `index_system`, so each match costs a row fetch. Adding the column makes it 37% faster at
50,000 rows and leaves a code-only search unchanged.

| `TokenIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| code only, `current` | 44 | 106 | 1,746 |
| code only, `withSystem` | 43 | 108 | 1,770 |
| system and code, `current` | 46 | 134 | 2,701 |
| system and code, `withSystem` | 45 | 116 | 1,694 |

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
`resourceUuid` sits between the equality prefix and the range columns, so no range can use the
index. Moving it last is 13–18% faster against a narrow window, about 4% of rows, and 5–7%
against a wide one, about 40%.

| `DateIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| narrow, `current` | 336 | 3,386 | 19,800 |
| narrow, `rangeLast` | 276 | 2,949 | 17,280 |
| wide, `current` | 455 | 4,806 | 28,560 |
| wide, `rangeLast` | 429 | 4,485 | 27,030 |

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

`syncDownload` reads and deserializes the whole pending queue once per page, only to intersect ids
with the page, so a download of new data slows as the queue grows: 4% at 1,000 pending changes.
The intersection also matches on resource id only: a downloaded Patient that shares an id with a
pending Observation change counts as a conflict, and resolving it throws
`ResourceNotFoundException`.

### Reads during a sync

`ConcurrentAccessBenchmark`, a prefix search over 20,000 patients, alone and while another thread
commits small transactions, µs:

| Journal | alone | with a writer |
|---|---:|---:|
| WAL (shipped) | 1,189 | 1,767 |
| rollback (`delete`) | 1,173 | 8,651 ±16% |

Under the rollback journal a writer holds an exclusive lock for its transaction and readers wait:
7x slower here, on tmpfs, and more on real storage. Under WAL the read pays 49%. The engine opens in
WAL, and `JournalModeTest` pins it.

## Settled questions

Answered by benchmarks that have since been removed; the code is in git history.

- **SQLite tuning.** `ANALYZE` and `synchronous` made no difference outside the noise.
- **Batch size.** Bigger transactions are faster, and the gain flattens after about a thousand
  resources. `DatabaseImpl` writes a batch in one transaction.
- **Protobuf payloads.** Half the disk and somewhat faster reads, against a destructive schema
  migration and a wire format that is not self-describing. Not adopted.
- **Network path.** Requesting and parsing a sync page costs well under a millisecond on the client;
  the time is in the network and the ingest.
