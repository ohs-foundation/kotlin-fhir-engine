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

import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.UriClientParam
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
 * A reference or uri lookup with the shipped index and with `resourceUuid` appended, which makes it
 * a covering index as the token index already is. Read cost only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class LookupIndexCoveringBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  @Param("Reference", "Uri") var table: String = ""

  /** `current` is what the engine ships; `covering` appends `resourceUuid`. */
  @Param("current", "covering") var shape: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("lookup-$table-$shape-$rows")
    database.seedLookups(rows)

    val columns = "`resourceType`, `index_name`, `index_value`"
    database.reindex(
      "${table}IndexEntity",
      when (shape) {
        "current" -> listOf(columns, "`resourceUuid`")
        "covering" -> listOf("$columns, `resourceUuid`", "`resourceUuid`")
        else -> error("Unknown index shape: $shape")
      },
    )

    val value =
      IndexBenchmarkDatabase.LOOKUP_VALUES[IndexBenchmarkDatabase.PROBE_ROW % LOOKUP_COUNT]
    query =
      Search(ResourceType.Observation)
        .apply {
          when (table) {
            "Reference" ->
              filter(ReferenceClientParam("subject"), { this.value = "Patient/$value" })
            "Uri" -> filter(UriClientParam("identifier"), { this.value = "urn:oid:$value" })
            else -> error("Unknown table: $table")
          }
        }
        .getQuery()

    // Only the covering arm should answer the subquery from the index alone.
    val plan = database.planFor(query).joinToString(" | ")
    check(("COVERING INDEX" in plan) == (shape == "covering")) {
      "arm '$shape' produced the wrong plan, so the arms measure the same thing: $plan"
    }

    assertSelectivity(database.count(query), rows, 1.0 / LOOKUP_COUNT, "$table-$shape")
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun lookupByValue(): Int = database.count(query)

  private companion object {
    val LOOKUP_COUNT = IndexBenchmarkDatabase.LOOKUP_VALUES.size
  }
}
