# Benchmark results

How the engine performs, measured by the micro benchmarks in `:engine` on CI. How to run them is in
[benchmarking.md](benchmarking.md).

## Machine

| | |
|---|---|
| Run | [CI run 37627989020](https://github.com/ohs-foundation/kotlin-fhir-engine/actions/runs/37627989020), `:engine:benchmark` at commit `4c302c57`, 7 October 2026 |
| Runner | GitHub-hosted `ubuntu-latest`, image `ubuntu24` 20261004.327.1 |
| CPU | AMD EPYC 7763, 4 vCPUs (2 cores, 2 threads each), 32 MiB L3 |
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
| Read one patient by id | 69 µs |
| Search patients by exact name, 1,000 patients | 90 µs |
| Search observations by code, 1,000 observations | 147 µs |
| Search patients by name prefix, 1,000 patients | 180 µs |
| Return an unfiltered first page of 50, 50,000 patients | 80 µs |
| Create a patient, indexing and local change included | 469 µs |
| Update a patient | 559 µs |
| Delete a patient | 287 µs |
| Index a patient's search parameters (FHIRPath, CPU only) | 216–229 µs |
| Parse a stored patient | 2.1–5.9 µs |
| Build a search query with five filters | 8.7 µs |
| Download and store 1,000 new patients in ten pages | 814 ms |
| Order 500 pending changes for upload | 262 µs |
| Open a database, first launch / later launches | 1.83 ms / 0.49 ms |

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
| string `:exact` | 90 | 92 |
| reference | 102 | 104 |
| token | 147 | 405 |
| number range | 152 | 462 |
| count | 157 | 459 |
| string prefix | 180 | 523 |
| string `:contains` | 188 | 516 |
| first page of fifty, unfiltered | 351 | 358 |
| `_revinclude` | 283 | 692 |
| **`_include`** | **3,112** | **64,735** |

The searches that seek stay flat as the corpus grows; the ones that narrow on two index columns
grow with it. `_include` costs 21x a plain token search at 1,000 and 160x at 5,000.

### Result size

`SearchResultSizeBenchmark`, µs, over 10,000 patients, varying only how many come back:

| Returned | page | with `_include` | with `_revinclude` |
|---:|---:|---:|---:|
| 1 | 92 | 184 | 184 |
| 100 | 599 | 8,216 | 2,057 |
| 1,000 | 5,101 | 30,958 | 84,145 |
| 10,000 | 52,401 | 789,006 | **7,504,774** |

A plain page costs about 5 µs per resource returned. A `_revinclude` over 10,000 takes 7.5 s.
`Search.execute` rescans the whole resolved list once per base resource, so the include columns
grow with the product of the page and the included resources. Grouping the resolved resources once,
by key, makes them linear and needs no schema change.

### Sorting

`SortBenchmark`, µs:

| | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| unsorted first page | 75 | 77 | 80 |
| sorted first page | 849 | 8,426 | **43,236** |
| unsorted, all rows | 184 | 1,343 | 8,460 |
| sorted, all rows | 928 | 11,682 | 80,327 |
| filtered to one name prefix, unsorted | 155 | 848 | 4,947 |
| filtered to one name prefix, sorted | 160 | 842 | 5,152 |

`count`/`from` does not bound the work on a sorted search. Rows do not arrive in order, so `LIMIT`
cannot stop early, and a sorted first page orders the whole corpus: 537x an unsorted page at 50,000.
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
| `binary` (shipped) | 148 | 831 | 5,030 |
| `nocase` | 89 | 104 | 171 |

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
| with a unit, `current` | 84 | 252 | 1,121 |
| with a unit, `codeFirst` | 72 | 178 | 693 |
| with a unit, `both` | 74 | 173 | 712 |
| without a unit, `current` | 114 | 653 | 3,249 |
| without a unit, `codeFirst` | 210 | 1,715 | 10,221 |
| without a unit, `both` | 113 | 654 | 3,247 |

**Reference and uri lookups are not covering.** Every filter subquery selects only `resourceUuid`;
the token index ends in that column and the reference and uri indices do not. Reference lookups
also carry chained, `_has` and `_revinclude` searches.

| `LookupIndexCoveringBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| reference, `current` | 73 | 210 | 3,618 |
| reference, `covering` | 73 | 178 | 2,410 |
| uri, `current` | 73 | 207 | 3,605 |
| uri, `covering` | 72 | 177 | 2,393 |

**Token search with a system** adds `IFNULL(index_system,'') = ?`, and the token index does not
carry `index_system`, so each match costs a row fetch. Adding the column makes it 35% faster at
50,000 rows and leaves a code-only search unchanged.

| `TokenIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| code only, `current` | 70 | 178 | 2,337 |
| code only, `withSystem` | 74 | 176 | 2,310 |
| system and code, `current` | 76 | 215 | 3,661 |
| system and code, `withSystem` | 73 | 182 | 2,380 |

**Date and dateTime** indices are `(resourceType, index_name, resourceUuid, index_from, index_to)`.
`resourceUuid` sits between the equality prefix and the range columns, so no range can use the
index. Moving it last is 12–15% faster against a selective window.

| `DateIndexShapeBenchmark` | 1,000 | 10,000 | 50,000 |
|---|---:|---:|---:|
| `current` | 518 | 5,173 | 28,671 |
| `rangeLast` | 440 | 4,505 | 25,119 |

## Writes and sync

| Operation | Time |
|---|---:|
| Create 50 patients through the engine | 23.5 ms ±10.8% |
| Update 50 patients | 28.0 ms ±9.3% |
| Delete 50 patients | 14.4 ms ±5.7% |
| Import a download page of 500, no local changes recorded | 255 ms ±3.7% |
| Read 50 pending changes / 500 | 152 µs / 873 µs |
| Squash 50 / 500 resources' changes into one patch each | 485 µs / 4,954 µs |
| Build upload requests for 50 / 500 resources | 157 µs / 1,555 µs |

`SyncDownloadBenchmark`, 1,000 patients in ten pages, ms:

| Pending local changes | 0 | 100 | 1,000 |
|---|---:|---:|---:|
| new patients (`insert`) | 814 | 827 | 910 |
| patients already stored (`update`) | 1,199 | 1,192 | 1,221 |

`syncDownload` reads and deserializes the whole pending queue once per page, only to intersect ids
with the page, so a download of new data slows as the queue grows: 12% at 1,000 pending changes.
The intersection also matches on resource id only: a downloaded Patient that shares an id with a
pending Observation change counts as a conflict, and resolving it throws
`ResourceNotFoundException`.

### Reads during a sync

`ConcurrentAccessBenchmark`, a prefix search over 20,000 patients, alone and while another thread
commits small transactions, µs:

| Journal | alone | with a writer |
|---|---:|---:|
| WAL (shipped) | 1,616 | 2,563 |
| rollback (`delete`) | 1,580 | 7,840 ±14% |

Under the rollback journal a writer holds an exclusive lock for its transaction and readers wait:
5x slower here, on tmpfs, and more on real storage. Under WAL the read pays 59%. The engine opens in
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
