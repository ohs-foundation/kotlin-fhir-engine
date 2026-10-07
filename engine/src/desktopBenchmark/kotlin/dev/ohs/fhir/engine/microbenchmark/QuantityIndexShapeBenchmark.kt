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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.search.QuantityClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
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
 * Measures quantity search with `QuantityIndexEntity` indexing the unit before or after the value.
 * An index serves a range only on the column right after its equality prefix.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class QuantityIndexShapeBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /**
   * `current` is what the engine ships, `codeFirst` moves the unit ahead of the value, and `both`
   * keeps the two side by side.
   */
  @Param("current", "codeFirst", "both") var shape: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var withUnit: SearchQuery
  private lateinit var withoutUnit: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("quantity-$shape-$rows")
    database.seedQuantity(rows)
    database.reindex(
      "QuantityIndexEntity",
      when (shape) {
        "current" -> listOf(VALUE_FIRST, FOREIGN_LOOKUP)
        "codeFirst" -> listOf(CODE_FIRST, FOREIGN_LOOKUP)
        "both" -> listOf(VALUE_FIRST, CODE_FIRST, FOREIGN_LOOKUP)
        else -> error("Unknown index shape: $shape")
      },
    )

    withUnit = quantitySearch(unit = PROBE_UNIT)
    withoutUnit = quantitySearch(unit = null)

    // The engine rewrites the code for units it can convert; PROBE_UNIT must stay verbatim.
    check(PROBE_UNIT in withUnit.args.map { it.toString() }) {
      "the engine no longer queries '$PROBE_UNIT' verbatim, so the seeded codes do not match it: " +
        withUnit.args
    }

    // `current` cannot narrow on the unit; the other arms must.
    val plan = database.planFor(withUnit).joinToString(" | ")
    check(("index_code=?" in plan) == (shape != "current")) {
      "arm '$shape' produced the wrong plan, so the arms measure the same thing: $plan"
    }

    assertSelectivity(
      database.count(withUnit),
      rows,
      (WINDOW / IndexBenchmarkDatabase.VALUE_SPREAD) / IndexBenchmarkDatabase.QUANTITY_UNITS.size,
      shape,
    )
  }

  @TearDown fun tearDown() = database.close()

  /** A value in a named unit. */
  @Benchmark fun quantitySearchWithUnit(): Int = database.count(withUnit)

  /**
   * No unit, so `codeFirst` cannot use the range. Matches more rows than [quantitySearchWithUnit];
   * compare it only across the arms.
   */
  @Benchmark fun quantitySearchWithoutUnit(): Int = database.count(withoutUnit)

  private fun quantitySearch(unit: String?): SearchQuery =
    Search(ResourceType.Observation)
      .apply {
        filter(
          QuantityClientParam(PARAM_NAME),
          {
            value = BigDecimal.parseString(PROBE_VALUE)
            if (unit != null) {
              system = IndexBenchmarkDatabase.UCUM_SYSTEM
              this.unit = unit
            }
          },
        )
      }
      .getQuery()

  private companion object {
    const val PARAM_NAME = "value-quantity"

    const val VALUE_FIRST = "`resourceType`, `index_name`, `index_value`, `index_code`"
    const val CODE_FIRST = "`resourceType`, `index_name`, `index_code`, `index_value`"
    const val FOREIGN_LOOKUP = "`resourceUuid`"

    /** A seeded unit that UCUM canonicalisation leaves unchanged. */
    const val PROBE_UNIT = "g/dL"

    /** Scale 0, so the engine matches [4.5, 5.5), a window of [WINDOW]. */
    const val PROBE_VALUE = "5"
    const val WINDOW = 1.0
  }
}
