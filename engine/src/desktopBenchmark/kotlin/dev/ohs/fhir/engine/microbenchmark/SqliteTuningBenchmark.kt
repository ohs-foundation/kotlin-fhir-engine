/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine.microbenchmark

import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

/**
 * What SQLite's own settings are worth to this schema.
 *
 * The engine sets no PRAGMAs and never runs ANALYZE, so it takes SQLite's defaults and its query
 * planner works from heuristics rather than statistics. Neither is obviously wrong, but neither has
 * ever been given a number, and both are cheap to change if they turn out to matter.
 *
 * Read and write are separate benchmarks because the settings pull in different directions: ANALYZE
 * only informs planning, while `journal_mode` and `synchronous` are almost entirely about what a
 * write has to durably record.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SqliteTuningBenchmark {

  /**
   * `default` is what the engine ships today.
   *
   * `analyze` doubles as a control for the two write benchmarks. ANALYZE only feeds the query
   * planner, so it cannot change what a write costs: any gap between it and `default` on an insert
   * is the machine's noise floor, not an effect. If that gap is larger than the gap between
   * `default` and the WAL arms, the run says nothing about WAL and should be repeated on an idle
   * machine.
   */
  @Param("default", "analyze", "wal", "walRelaxed") var tuning: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery
  private var batch = 0

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("tuning-$tuning")
    // Applied before seeding, so the write benchmark and the seed both run under the setting.
    when (tuning) {
      "default" -> Unit
      "analyze" -> Unit
      "wal" -> database.pragma("journal_mode=WAL")
      "walRelaxed" -> {
        database.pragma("journal_mode=WAL")
        database.pragma("synchronous=NORMAL")
      }
      else -> error("Unknown tuning: $tuning")
    }
    database.seed(ROWS)
    // After seeding, because statistics gathered over empty tables describe nothing.
    if (tuning == "analyze") database.analyze()

    val prefix = IndexBenchmarkDatabase.prefixFor(PROBE_ROW)
    query =
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("given"), { value = prefix }) }
        .getQuery()
    check(database.count(query) > 0) { "the probe prefix matches nothing at $ROWS rows" }
  }

  /**
   * The write benchmarks append without removing, so the table would grow all through a run and
   * every later invocation would measure a larger index than the one before it. Resetting per
   * iteration bounds that drift; it costs nothing for [prefixSearch], which deletes no rows.
   */
  @Setup(Level.Iteration)
  fun resetInsertedRows() {
    database.deleteInsertedRows()
  }

  @TearDown fun tearDown() = database.close()

  /** Planning is where ANALYZE can help; the query is unremarkable on purpose. */
  @Benchmark fun prefixSearch(): Int = database.count(query)

  /**
   * An indexed write in one transaction. Journal settings have least to offer here: whatever a
   * commit costs is paid once and spread across every row in it.
   */
  @Benchmark
  fun insertIndexedRowsBatched() {
    database.insertIndexRows(BATCHED_INSERTS, batch++)
  }

  /**
   * The same write split one row per transaction, where the commit cost is paid in full each time.
   * This is where a journal setting can actually show itself.
   *
   * Not comparable to [insertIndexedRowsBatched] — it writes fewer rows, deliberately, to keep the
   * run bounded. Compare each across the tuning arms, never against the other.
   */
  @Benchmark
  fun insertIndexedRowsPerTransaction() {
    database.insertIndexRowsPerTransaction(SINGLE_INSERTS, batch++)
  }

  private companion object {
    const val ROWS = 20_000
    const val BATCHED_INSERTS = 500

    /** Fewer, because each one pays a commit; enough that the per-commit cost is not noise. */
    const val SINGLE_INSERTS = 50
    const val PROBE_ROW = 42
  }
}
