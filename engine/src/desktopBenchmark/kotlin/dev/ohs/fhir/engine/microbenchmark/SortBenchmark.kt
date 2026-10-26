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

import dev.ohs.fhir.engine.search.Order
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
 * Measures what sorting a search costs, and whether paging avoids it. No index backs a sorted
 * search, so SQLite groups and orders in temporary B-trees.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SortBenchmark {

  @Param("1000", "10000", "50000") var rows: Int = 0

  private lateinit var database: IndexBenchmarkDatabase
  private lateinit var unsorted: SearchQuery
  private lateinit var sorted: SearchQuery
  private lateinit var unsortedFirstPage: SearchQuery
  private lateinit var sortedFirstPage: SearchQuery
  private lateinit var filteredUnsorted: SearchQuery
  private lateinit var filteredSorted: SearchQuery

  @Setup
  fun setUp() {
    database = IndexBenchmarkDatabase.create("sort-$rows")
    database.seed(rows)

    unsorted = search()
    sorted = search(sort = true)
    unsortedFirstPage = search(page = true)
    sortedFirstPage = search(sort = true, page = true)
    filteredUnsorted = search(filterPrefix = probePrefix())
    filteredSorted = search(filterPrefix = probePrefix(), sort = true)

    val unsortedCount = database.count(unsorted)
    check(unsortedCount == rows && database.count(sorted) == rows) {
      "sorted and unsorted searches disagree at $rows rows: $unsortedCount against " +
        database.count(sorted)
    }
    check(database.count(sortedFirstPage) == PAGE) {
      "a sorted first page returned ${database.count(sortedFirstPage)} rows, expected $PAGE"
    }
    assertSelectivity(
      database.count(filteredSorted),
      rows,
      1.0 / IndexBenchmarkDatabase.PREFIX_COMBINATIONS,
      "filtered",
    )
  }

  @TearDown fun tearDown() = database.close()

  /** Every resource of the type, in storage order. */
  @Benchmark fun unsortedAll(): Int = database.count(unsorted)

  /** The same resources ordered by a string parameter. */
  @Benchmark fun sortedAll(): Int = database.count(sorted)

  /** One page with no ordering, which a LIMIT can stop early on. */
  @Benchmark fun unsortedFirstPage(): Int = database.count(unsortedFirstPage)

  /** One page in order. Compare with [unsortedFirstPage]. */
  @Benchmark fun sortedFirstPage(): Int = database.count(sortedFirstPage)

  /** One prefix slice, unordered. */
  @Benchmark fun filteredUnsorted(): Int = database.count(filteredUnsorted)

  /** The same slice, ordered. */
  @Benchmark fun filteredSorted(): Int = database.count(filteredSorted)

  private fun search(
    filterPrefix: String? = null,
    sort: Boolean = false,
    page: Boolean = false,
  ): SearchQuery =
    Search(
        type = ResourceType.Patient,
        count = if (page) PAGE else null,
        from = if (page) 0 else null,
      )
      .apply {
        if (filterPrefix != null) filter(StringClientParam(SORT_PARAM), { value = filterPrefix })
        if (sort) sort(StringClientParam(SORT_PARAM), Order.ASCENDING)
      }
      .getQuery()

  private fun probePrefix() = IndexBenchmarkDatabase.prefixFor(IndexBenchmarkDatabase.PROBE_ROW)

  private companion object {
    /** The parameter `IndexBenchmarkDatabase.seed` writes string index rows for. */
    const val SORT_PARAM = "given"

    const val PAGE = 50
  }
}
