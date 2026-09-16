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
package dev.ohs.fhir.engine.search.query

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.db.impl.ResourceDatabase
import dev.ohs.fhir.engine.search.DateClientParam
import dev.ohs.fhir.engine.search.NumberClientParam
import dev.ohs.fhir.engine.search.Order
import dev.ohs.fhir.engine.search.QuantityClientParam
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.StringFilterModifier
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.UriClientParam
import dev.ohs.fhir.engine.search.filter.TokenFilterValue
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/**
 * Asserts which SQLite index each search shape actually uses, via `EXPLAIN QUERY PLAN`.
 *
 * These are not benchmarks. A lost index only shows up in a timing run at a large corpus, and a
 * warm page cache hides it even then; the query plan says so immediately, deterministically, and
 * against an empty database. Timing answers "how slow", this answers "why".
 *
 * The plan text comes from SQLite, so it is checked by substring rather than equality — the wording
 * varies between versions, but the index name and the constrained columns are the part that
 * matters.
 *
 * Two of these tests pin behaviour that is **suboptimal but current**, and say so in their names.
 * If a schema change fixes one, that test will fail: read the comment, confirm the plan improved,
 * and tighten the assertion.
 */
class SearchQueryPlanTest {

  private lateinit var database: ResourceDatabase

  @BeforeTest
  fun setUp() {
    database =
      Room.inMemoryDatabaseBuilder<ResourceDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
  }

  @AfterTest fun tearDown() = database.close()

  @Test
  fun `no search shape falls back to a full table scan`() = runTest {
    for ((name, query) in allShapes()) {
      val scans = planFor(query).filter { it.startsWith("SCAN ") && !it.contains("USING") }
      assertTrue(scans.isEmpty(), "$name scans a table instead of using an index: $scans")
    }
  }

  @Test
  fun `token search is answered entirely from a covering index`() = runTest {
    val plan = planFor(tokenSearch())

    // The only index table whose index ends in resourceUuid, so the subquery never touches a row.
    assertIndexUsed(
      plan,
      table = "TokenIndexEntity",
      constraints = "resourceType=? AND index_name=? AND index_value=?",
      covering = true,
    )
  }

  @Test
  fun `reference search narrows on all three index columns`() = runTest {
    assertIndexUsed(
      planFor(referenceSearch()),
      table = "ReferenceIndexEntity",
      constraints = "resourceType=? AND index_name=? AND index_value=?",
    )
  }

  @Test
  fun `uri search narrows on all three index columns`() = runTest {
    assertIndexUsed(
      planFor(uriSearch()),
      table = "UriIndexEntity",
      constraints = "resourceType=? AND index_name=? AND index_value=?",
    )
  }

  @Test
  fun `number search uses the index for the value range`() = runTest {
    assertIndexUsed(
      planFor(numberSearch()),
      table = "NumberIndexEntity",
      constraints = "resourceType=? AND index_name=? AND index_value>? AND index_value<?",
    )
  }

  @Test
  fun `quantity search uses the index for the value range`() = runTest {
    assertIndexUsed(
      planFor(quantitySearch()),
      table = "QuantityIndexEntity",
      constraints = "resourceType=? AND index_name=? AND index_value>? AND index_value<?",
    )
  }

  /**
   * `index_value` is NOCASE and the prefix pattern is bound whole, which together let SQLite
   * rewrite `LIKE 'x%'` into a range scan — hence the `>` and `<` bounds rather than an equality.
   * Both halves are required: the optimisation is skipped if the pattern is an expression, and
   * skipped again if the index's collation does not match the comparison's.
   *
   * Worth 38x on a 50,000-row table; see `StringIndexCollationBenchmark`.
   */
  @Test
  fun `prefix string search narrows on index_value through the LIKE range optimisation`() =
    runTest {
      assertIndexUsed(
        planFor(stringSearch()),
        table = "StringIndexEntity",
        constraints = "resourceType=? AND index_name=? AND index_value>? AND index_value<?",
      )
    }

  /**
   * The deliberate cost of the above, pinned so it stays visible. FHIR's `:exact` is
   * case-sensitive, so it compares `COLLATE BINARY` against a NOCASE index, and SQLite will not use
   * an index whose collation differs from the comparison's. `:exact` therefore narrows on
   * `(resourceType, index_name)` and examines the rest.
   *
   * Indexing both would need a second, BINARY-collated column mirroring `index_value` — a disk and
   * write cost for the rarer of the two paths, so it is not done.
   */
  @Test
  fun `exact string search cannot use index_value because it compares COLLATE BINARY`() = runTest {
    assertIndexUsed(
      planFor(exactStringSearch()),
      table = "StringIndexEntity",
      constraints = "resourceType=? AND index_name=?",
    )
  }

  /**
   * Suboptimal, pinned deliberately. `index_DateIndexEntity_resourceType_index_name_resourceUuid_
   * index_from_index_to` places `resourceUuid` between the equality columns and the range columns.
   * An index can only serve a range predicate on the column immediately after its equality prefix,
   * so neither `index_to > ?` (`gt`, `ge`, `eb`) nor `index_from < ?` (`sa`, `lt`, `le`) is usable
   * and both narrow on `(resourceType, index_name)` alone.
   *
   * Moving `resourceUuid` to the end and adding a second index leading with `index_to` does make
   * the ranges usable. It was tried, and measured **worse** end to end: about 13% slower on both a
   * date range search and a delete, against a 1,000-patient corpus. At that size a covering scan of
   * the equality prefix beats a seek, and a range spanning two subqueries then needs two separate
   * index traversals instead of sharing one. `DateIndexShapeBenchmark` sweeps the same change up to
   * 50,000 rows; see "Index usage" in docs/benchmarking.md.
   */
  @Test
  fun `date search above a bound cannot use the range columns`() = runTest {
    assertIndexUsed(
      planFor(dateSearch()),
      table = "DateIndexEntity",
      constraints = "resourceType=? AND index_name=?",
      covering = true,
    )
  }

  /** The other comparator family, blocked by the same column ordering. */
  @Test
  fun `date search below a bound cannot use the range columns`() = runTest {
    assertIndexUsed(
      planFor(dateBeforeSearch()),
      table = "DateIndexEntity",
      constraints = "resourceType=? AND index_name=?",
      covering = true,
    )
  }

  /**
   * Sorting is not index-backed: the plan builds two temporary B-trees. Worth knowing before anyone
   * reads a sorted-search benchmark and blames the filter.
   */
  @Test
  fun `sorted search sorts with a temporary b-tree rather than an index`() = runTest {
    val plan = planFor(sortedSearch())

    assertTrue(
      plan.any { it.contains("USE TEMP B-TREE FOR ORDER BY") },
      "expected a temporary b-tree sort, got: $plan",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Search shapes
  // ---------------------------------------------------------------------------------------------

  private fun allShapes(): List<Pair<String, SearchQuery>> =
    listOf(
      "string" to stringSearch(),
      "string exact" to exactStringSearch(),
      "token" to tokenSearch(),
      "reference" to referenceSearch(),
      "quantity" to quantitySearch(),
      "date" to dateSearch(),
      "date before" to dateBeforeSearch(),
      "number" to numberSearch(),
      "uri" to uriSearch(),
      "sorted" to sortedSearch(),
      "count" to countSearch(),
    )

  private fun stringSearch() =
    Search(ResourceType.Patient)
      .apply { filter(StringClientParam("given"), { value = "Ja" }) }
      .getQuery()

  private fun exactStringSearch() =
    Search(ResourceType.Patient)
      .apply {
        filter(
          StringClientParam("given"),
          {
            value = "Jane"
            modifier = StringFilterModifier.MATCHES_EXACTLY
          },
        )
      }
      .getQuery()

  private fun dateBeforeSearch() =
    Search(ResourceType.Patient)
      .apply {
        filter(
          DateClientParam("birthdate"),
          {
            value = of(FhirDate.Date(LocalDate(2000, 1, 1)))
            prefix = SearchComparator.Lt
          },
        )
      }
      .getQuery()

  private fun tokenSearch() =
    Search(ResourceType.Patient)
      .apply { filter(TokenClientParam("gender"), { value = TokenFilterValue.string("male") }) }
      .getQuery()

  private fun referenceSearch() =
    Search(ResourceType.Patient)
      .apply {
        filter(ReferenceClientParam("organization"), { value = "Organization/organization-1" })
      }
      .getQuery()

  private fun quantitySearch() =
    Search(ResourceType.Observation)
      .apply {
        filter(QuantityClientParam("value-quantity"), { value = BigDecimal.parseString("5.4") })
      }
      .getQuery()

  private fun dateSearch() =
    Search(ResourceType.Patient)
      .apply {
        filter(
          DateClientParam("birthdate"),
          {
            value = of(FhirDate.Date(LocalDate(1970, 1, 1)))
            prefix = SearchComparator.Gt
          },
        )
      }
      .getQuery()

  private fun numberSearch() =
    Search(ResourceType.RiskAssessment)
      .apply { filter(NumberClientParam("probability"), { value = BigDecimal.parseString("0.5") }) }
      .getQuery()

  private fun uriSearch() =
    Search(ResourceType.Patient)
      .apply { filter(UriClientParam("identifier"), { value = "urn:oid:1.2.3" }) }
      .getQuery()

  private fun sortedSearch() =
    Search(ResourceType.Patient)
      .apply { sort(StringClientParam("given"), Order.ASCENDING) }
      .getQuery()

  private fun countSearch() =
    Search(ResourceType.Patient)
      .apply { filter(StringClientParam("given"), { value = "Ja" }) }
      .getQuery(isCount = true)

  // ---------------------------------------------------------------------------------------------
  // Plan helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts [table] is searched through an index constrained by exactly [constraints]. The
   * constraint list is SQLite's own rendering of which columns it could narrow on, which is the
   * thing that decides whether the index is doing its job.
   */
  private fun assertIndexUsed(
    plan: List<String>,
    table: String,
    constraints: String,
    covering: Boolean = false,
  ) {
    val line =
      plan.firstOrNull { it.startsWith("SEARCH $table USING") }
        ?: fail("no indexed search of $table in plan: $plan")

    assertTrue(
      line.contains("($constraints)"),
      "$table should narrow on ($constraints), but the plan says: $line",
    )
    if (covering) {
      assertTrue(line.contains("USING COVERING INDEX"), "$table should be covering, got: $line")
    }
  }

  /** The `detail` column of `EXPLAIN QUERY PLAN`, one entry per plan step. */
  private suspend fun planFor(query: SearchQuery): List<String> =
    database.useReaderConnection { transactor ->
      transactor.usePrepared("EXPLAIN QUERY PLAN ${query.query}") { statement ->
        bindArgs(statement, query.args)
        val steps = mutableListOf<String>()
        while (statement.step()) steps += statement.getText(3)
        steps
      }
    }

  /** Mirrors `DatabaseImpl.bindArgs`, which is private to that class. */
  private fun bindArgs(statement: SQLiteStatement, args: List<Any>) {
    args.forEachIndexed { i, arg ->
      when (arg) {
        is String -> statement.bindText(i + 1, arg)
        is Long -> statement.bindLong(i + 1, arg)
        is Double -> statement.bindDouble(i + 1, arg)
        is Int -> statement.bindLong(i + 1, arg.toLong())
        else -> statement.bindText(i + 1, arg.toString())
      }
    }
  }
}
