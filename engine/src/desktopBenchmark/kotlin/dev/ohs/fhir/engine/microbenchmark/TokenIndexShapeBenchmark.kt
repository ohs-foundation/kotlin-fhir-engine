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
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.filter.TokenFilterValue
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
 * Measures token search with and without a system, against the token index with and without
 * `index_system`. Without it, the system predicate costs a row fetch per match.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class TokenIndexShapeBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /** `current` is what the engine ships; `withSystem` adds `index_system` before `resourceUuid`. */
  @Param("current", "withSystem") var shape: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var codeOnly: SearchQuery
  private lateinit var systemAndCode: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("token-$shape-$rows")
    database.seedTokens(rows)
    database.reindex(
      "TokenIndexEntity",
      when (shape) {
        "current" -> listOf("`resourceType`, `index_name`, `index_value`, `resourceUuid`", UUID)
        "withSystem" ->
          listOf(
            "`resourceType`, `index_name`, `index_value`, `index_system`, `resourceUuid`",
            UUID,
          )
        else -> error("Unknown index shape: $shape")
      },
    )

    val code = IndexBenchmarkDatabase.LOOKUP_VALUES[IndexBenchmarkDatabase.PROBE_ROW % CODE_COUNT]
    codeOnly = tokenSearch(TokenFilterValue.string(code))
    systemAndCode = tokenSearch(TokenFilterValue.coding(IndexBenchmarkDatabase.TOKEN_SYSTEM, code))

    check("COVERING INDEX" in database.planFor(codeOnly).joinToString(" | ")) {
      "arm '$shape' no longer answers a code-only search from a covering index"
    }
    val plan = database.planFor(systemAndCode).joinToString(" | ")
    check(("COVERING INDEX" in plan) == (shape == "withSystem")) {
      "arm '$shape' produced the wrong plan, so the arms measure the same thing: $plan"
    }

    assertSelectivity(database.count(codeOnly), rows, 1.0 / CODE_COUNT, "$shape-code")
    assertSelectivity(database.count(systemAndCode), rows, 1.0 / CODE_COUNT, "$shape-system")
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun searchByCode(): Int = database.count(codeOnly)

  @Benchmark fun searchBySystemAndCode(): Int = database.count(systemAndCode)

  private fun tokenSearch(filterValue: TokenFilterValue): SearchQuery =
    Search(ResourceType.Observation)
      .apply { filter(TokenClientParam("code"), { value = filterValue }) }
      .getQuery()

  private companion object {
    const val UUID = "`resourceUuid`"

    val CODE_COUNT = IndexBenchmarkDatabase.LOOKUP_VALUES.size
  }
}
