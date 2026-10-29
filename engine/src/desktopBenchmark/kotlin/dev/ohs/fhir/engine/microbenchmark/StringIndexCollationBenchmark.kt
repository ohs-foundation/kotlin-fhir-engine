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

/**
 * Measures a prefix search with `StringIndexEntity.index_value` indexed BINARY or NOCASE. The
 * prefix `LIKE` becomes a range seek only with a NOCASE index.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class StringIndexCollationBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /** `nocase` is what the engine ships; `binary` is the index before it. */
  @Param("binary", "nocase") var collation: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("string-$collation-$rows")
    database.seed(rows)
    database.reindex(
      "StringIndexEntity",
      when (collation) {
        // `index_value` is declared NOCASE, and an index inherits that unless it names BINARY.
        "binary" ->
          listOf(
            "`resourceType`, `index_name`, `index_value` COLLATE BINARY",
            "`resourceUuid`, `index_name`, `index_value` COLLATE BINARY",
          )
        "nocase" ->
          listOf(
            "`resourceType`, `index_name`, `index_value` COLLATE NOCASE",
            "`resourceUuid`, `index_name`, `index_value` COLLATE NOCASE",
          )
        else -> error("Unknown collation: $collation")
      },
    )

    val prefix = IndexBenchmarkDatabase.prefixFor(IndexBenchmarkDatabase.PROBE_ROW)
    query =
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("given"), { value = prefix }) }
        .getQuery()

    // `binary` must not narrow on index_value; `nocase` must.
    val plan = database.planFor(query).joinToString(" | ")
    val narrowsOnValue = "index_value>?" in plan
    check(narrowsOnValue == (collation == "nocase")) {
      "arm '$collation' produced the wrong plan, so the arms measure the same thing: $plan"
    }

    assertSelectivity(
      database.count(query),
      rows,
      1.0 / IndexBenchmarkDatabase.PREFIX_COMBINATIONS,
      collation,
    )
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun prefixSearch(): Int = database.count(query)
}
