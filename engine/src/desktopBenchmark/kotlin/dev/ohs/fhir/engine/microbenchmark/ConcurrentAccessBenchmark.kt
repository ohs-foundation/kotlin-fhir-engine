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
import kotlin.concurrent.Volatile
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
 * What a read costs while another thread writes, under WAL and under a rollback journal. Compare
 * each [journal] arm with its own `writers = 0` floor, not across arms.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ConcurrentAccessBenchmark {

  /** `wal` is what Room opens with; `delete` is SQLite's rollback journal, asked for by name. */
  @Param("wal", "delete") var journal: String = ""

  /** `0` reads alone; `1` adds a thread that commits small transactions in a loop. */
  @Param("0", "1") var writers: Int = 0

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery

  @Volatile private var writing = false
  private var writer: Thread? = null
  private var batch = 0

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("concurrent-$journal-$writers")
    when (journal) {
      "wal" -> database.pragma("journal_mode=WAL")
      "delete" -> database.pragma("journal_mode=DELETE")
      else -> error("Unknown journal mode: $journal")
    }
    database.seed(ROWS)

    val mode = database.pragmaValue("journal_mode").lowercase()
    check(mode == journal) {
      "arm '$journal' is running in '$mode', so the journal arms do not differ"
    }

    val prefix = IndexBenchmarkDatabase.prefixFor(IndexBenchmarkDatabase.PROBE_ROW)
    query =
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("given"), { value = prefix }) }
        .getQuery()
    assertSelectivity(
      database.count(query),
      ROWS,
      1.0 / IndexBenchmarkDatabase.PREFIX_COMBINATIONS,
      journal,
    )
  }

  /** Per iteration, not per invocation: starting a thread costs more than the read. */
  @Setup(Level.Iteration)
  fun startWriting() {
    if (writers == 0) return
    writing = true
    writer =
      Thread {
          while (writing) {
            database.insertIndexRowsPerTransaction(WRITES_PER_ROUND, batch++)
          }
        }
        .apply { start() }
  }

  /** Deletes the writer's rows so the table does not grow across iterations. */
  @TearDown(Level.Iteration)
  fun stopWriting() {
    writing = false
    writer?.join()
    writer = null
    database.deleteInsertedRows()
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun prefixSearch(): Int = database.count(query)

  private companion object {
    const val ROWS = 20_000

    /** Small transactions, so the writer takes and releases the lock constantly. */
    const val WRITES_PER_ROUND = 20
  }
}
