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

import dev.ohs.fhir.engine.search.DateClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
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
import kotlinx.datetime.LocalDate

/**
 * A date range search against the shipped date index and one with the range columns ahead of
 * `resourceUuid`, across table sizes. Indices are rebuilt with raw DDL per trial.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class DateIndexShapeBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /** `current` is what the engine ships; `rangeLast` puts `resourceUuid` last. */
  @Param("current", "rangeLast") var shape: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("date-$shape-$rows")
    database.seed(rows)
    database.reindex("DateIndexEntity", indexDefinitions())

    query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.Date(LocalDate(1990, 1, 1)))
              prefix = SearchComparator.Gt
            },
          )
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.Date(LocalDate(1992, 1, 1)))
              prefix = SearchComparator.Lt
            },
          )
        }
        .getQuery()

    // Both arms must select the same slice.
    assertSelectivity(database.count(query), rows, WINDOW_DAYS.toDouble() / DAY_SPREAD, shape)
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun dateRangeSearch(): Int = database.count(query)

  private companion object {
    /** 1990-01-01 to 1992-01-01, against [IndexBenchmarkDatabase.DAY_SPREAD]. */
    const val WINDOW_DAYS = 730
    const val DAY_SPREAD = IndexBenchmarkDatabase.DAY_SPREAD
  }

  private fun indexDefinitions(): List<String> =
    when (shape) {
      "current" ->
        listOf(
          "`resourceType`, `index_name`, `resourceUuid`, `index_from`, `index_to`",
          "`resourceUuid`, `index_name`, `index_from`",
        )
      // The second index serves comparators that range over index_to.
      "rangeLast" ->
        listOf(
          "`resourceType`, `index_name`, `index_from`, `index_to`, `resourceUuid`",
          "`resourceType`, `index_name`, `index_to`, `resourceUuid`",
          "`resourceUuid`, `index_name`, `index_from`",
        )
      else -> error("Unknown index shape: $shape")
    }
}
