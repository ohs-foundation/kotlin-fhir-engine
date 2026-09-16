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
import dev.ohs.fhir.engine.search.StringClientParam
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
 * Does the date index's column order matter, and at what size?
 *
 * `DateIndexEntity`'s filtering index is `(resourceType, index_name, resourceUuid, index_from,
 * index_to)`. A range predicate can only use the column after the equality prefix, and
 * `resourceUuid` sits in between, so no date comparator can range — confirmed by
 * `SearchQueryPlanTest`. Reordering it has been tried end to end, against a real engine and a
 * 1,000-patient corpus, and measured about 13% *worse* on both a date range search and a delete.
 * The open question is whether that reverses once the table is large enough for a seek to beat a
 * scan, which is what [rows] sweeps.
 *
 * Indices are rebuilt with raw DDL per trial, so both shapes are measured in one JVM against one
 * schema — no source edits, no rebuilds, and no hand-rolled interleaving, because JMH already forks
 * and warms each combination and reports an error bar.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class DateIndexShapeBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /** `current` is what the engine ships; `rangeLast` is the reordering under evaluation. */
  @Param("current", "rangeLast") var shape: String = ""

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var query: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("date-$shape-$rows")
    database.seed(rows)
    database.reindex("DateIndexEntity", indexDefinitions())

    // A window inside the seeded spread, so the range selects a slice at every size rather than
    // everything at one end of the curve and nothing at the other.
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

    // Both arms must select the same slice, or the comparison is between two different queries
    // rather than two index shapes. The window is 730 days of the seeded spread, so the expected
    // count is a fixed fraction of the rows at every size.
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
      // resourceUuid third, blocking the range columns behind it.
      "current" ->
        listOf(
          "`resourceType`, `index_name`, `resourceUuid`, `index_from`, `index_to`",
          "`resourceUuid`, `index_name`, `index_from`",
        )
      // resourceUuid last so the ranges are reachable, plus a second index for the comparators
      // that range over index_to rather than index_from.
      "rangeLast" ->
        listOf(
          "`resourceType`, `index_name`, `index_from`, `index_to`, `resourceUuid`",
          "`resourceType`, `index_name`, `index_to`, `resourceUuid`",
          "`resourceUuid`, `index_name`, `index_from`",
        )
      else -> error("Unknown index shape: $shape")
    }
}

/**
 * Does making `StringIndexEntity.index_value` NOCASE pay for itself, and at what size?
 *
 * A prefix search compiles to `index_value LIKE ? || '%' COLLATE NOCASE`, which cannot use a BINARY
 * index. Declaring the column NOCASE *and* binding the pattern whole turns it into a range seek.
 * That combination measured as no change at 1,000 patients; [rows] asks whether size changes the
 * answer.
 *
 * The two arms differ in both the index collation and the SQL, because either alone leaves the
 * optimisation off — which is the trap that made the first attempt at this look like a failure.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class StringIndexCollationBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  /** `binary` is what the engine ships; `nocase` is the collation plus the bound-pattern SQL. */
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
        // COLLATE BINARY is explicit because `index_value` is declared NOCASE: an index over it
        // inherits that collation, so without this the "binary" arm is a second NOCASE arm and the
        // comparison silently measures nothing.
        "binary" ->
          listOf(
            "`resourceType`, `index_name`, `index_value` COLLATE BINARY",
            "`resourceUuid`, `index_name`, `index_value` COLLATE BINARY",
          )
        "nocase" ->
          listOf(
            "`resourceType`, `index_name`, `index_value`",
            "`resourceUuid`, `index_name`, `index_value`",
          )
        else -> error("Unknown collation: $collation")
      },
    )

    val prefix = IndexBenchmarkDatabase.prefixFor(PROBE_ROW)
    query =
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("given"), { value = prefix }) }
        .getQuery()
    // The engine now binds the pattern whole, so `getQuery()` returns the optimised form. The
    // `binary` arm reconstructs what it used to emit: a pattern concatenated in SQL, which the LIKE
    // optimisation cannot see into, against a BINARY index it could not use anyway.
    if (collation == "binary") {
      query =
        SearchQuery(
          query.query.replace("index_value LIKE ?", "index_value LIKE ? || '%' COLLATE NOCASE"),
          query.args.dropLast(1) + prefix,
        )
    }

    // Prove the arms actually differ before timing them. `binary` must fail to narrow on
    // index_value and `nocase` must succeed; if both ever agree the comparison is vacuous.
    val plan = database.planFor(query).joinToString(" | ")
    val narrowsOnValue = "index_value>?" in plan
    check(narrowsOnValue == (collation == "nocase")) {
      "arm '$collation' produced the wrong plan, so the two arms are not measuring different " +
        "things: $plan"
    }

    // One of 676 two-letter prefixes, so both arms must land on the same small slice.
    assertSelectivity(database.count(query), rows, 1.0 / PREFIX_COMBINATIONS, collation)
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark fun prefixSearch(): Int = database.count(query)

  private companion object {
    /** Any row; its two-letter prefix selects roughly 1/676 of the table at any size. */
    const val PROBE_ROW = 42
    const val PREFIX_COMBINATIONS = 26 * 26
  }
}

/**
 * Fails a trial whose query does not select the slice it was designed to.
 *
 * Selectivity is the whole game for an index: a predicate matching half the table cannot be helped
 * by one, and a predicate matching nothing is not being measured at all. This was learned the hard
 * way: an end-to-end suite whose generated corpus held eight given names matched an eighth of it on
 * every prefix search, and so could never show a benefit from any index change at all.
 */
private fun assertSelectivity(matched: Int, rows: Int, expectedFraction: Double, arm: String) {
  val expected = rows * expectedFraction
  // A percentage band alone is too tight where the expected count is only a row or two, and whole
  // rows cannot land on a fraction. Allow whichever is looser: a fifth, or a single row.
  val tolerance = maxOf(1.0, expected * 0.2)
  check(matched > 0 && matched >= expected - tolerance && matched <= expected + tolerance) {
    "arm '$arm' at $rows rows matched $matched, expected about ${expected.toInt()}. The query is " +
      "no longer selecting the slice this benchmark assumes, so its timings are not comparable."
  }
}
