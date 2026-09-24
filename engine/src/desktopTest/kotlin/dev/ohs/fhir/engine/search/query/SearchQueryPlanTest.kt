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
import dev.ohs.fhir.engine.search.QuantityClientParam
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.StringFilterModifier
import dev.ohs.fhir.engine.search.UriClientParam
import dev.ohs.fhir.engine.search.getIncludeQuery
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.engine.search.getRevIncludeQuery
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Asserts which SQLite index each search shape actually uses, via `EXPLAIN QUERY PLAN`.
 *
 * These are not benchmarks. A lost index only shows up in a timing run at a large corpus, and a
 * warm page cache hides it even then; the query plan says so immediately and against an empty
 * database. Timing answers how slow, this answers why.
 *
 * The plan text comes from SQLite, so it is checked by substring rather than equality: the wording
 * varies between versions, but the index name and the constrained columns are what matter.
 *
 * Some tests pin behaviour that is suboptimal but current, and say so in their names. If a schema
 * change fixes one, that test will fail: read the comment, confirm the plan improved, and tighten
 * the assertion.
 */
class SearchQueryPlanTest {

  private lateinit var database: ResourceDatabase

  private val BASE_UUID_A = "00000000-0000-0000-0000-000000000001"
  private val BASE_UUID_B = "00000000-0000-0000-0000-000000000002"

  @BeforeTest
  fun setUp() {
    database =
      Room.inMemoryDatabaseBuilder<ResourceDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
  }

  @AfterTest fun tearDown() = database.close()

  /**
   * A NOCASE column plus a whole bound pattern let SQLite rewrite `LIKE 'x%'` into a range scan,
   * hence the `>` and `<` bounds. Worth 38x on a 50,000-row table; `StringIndexCollationBenchmark`
   * sweeps it.
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
   * The deliberate cost of the above, pinned so it stays visible. `:exact` compares BINARY against
   * a NOCASE index, which SQLite will not use. Indexing both needs a second column.
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
   * A leading wildcard rules out a range seek whatever the collation, so this one cannot be fixed.
   */
  @Test
  fun `contains string search cannot narrow on index_value`() = runTest {
    assertIndexUsed(
      planFor(containsStringSearch()),
      table = "StringIndexEntity",
      constraints = "resourceType=? AND index_name=?",
    )
  }

  /**
   * The included resource is reached by a unique-index seek. A join comparing an expression against
   * `rie.index_value` cannot seek, and costs the product of the two tables instead.
   */
  @Test
  fun `include search seeks the resource it references`() = runTest {
    val plan = planFor(includeQuery())

    assertIndexUsed(plan, table = "re", constraints = "resourceType=? AND resourceId=?")
    // A shortfall, pinned. `rie.resourceUuid IN (...)` could drive the lookup through
    // index_ReferenceIndexEntity_resourceUuid, but SQLite prefers the wider index and walks the
    // parameter's reference rows. Forcing the other index costs more whenever a search includes
    // many base resources.
    assertIndexUsed(plan, table = "rie", constraints = "resourceType=? AND index_name=?")
  }

  /** The counterpart: it binds the `type/id` strings from Kotlin, so both sides seek. */
  @Test
  fun `revinclude search seeks both sides of its join`() = runTest {
    val plan = planFor(revIncludeQuery())

    assertIndexUsed(
      plan,
      table = "rie",
      constraints = "resourceType=? AND index_name=? AND index_value=?",
    )
    assertIndexUsed(plan, table = "re", constraints = "resourceUuid=?")
  }

  /**
   * A quantity search naming a unit compares the unit for equality before ranging on the value, so
   * it needs the index led by `index_code`. Without it the range spans every unit recorded for the
   * parameter.
   */
  @Test
  fun `quantity search naming a unit narrows on the unit`() = runTest {
    assertIndexUsed(
      planFor(quantitySearchWithUnit()),
      table = "QuantityIndexEntity",
      constraints =
        "resourceType=? AND index_name=? AND index_code=? AND index_value>? AND index_value<?",
    )
  }

  /** The other order still serves a search that omits the unit, which is why both are kept. */
  @Test
  fun `quantity search without a unit ranges on the value`() = runTest {
    assertIndexUsed(
      planFor(quantitySearch()),
      table = "QuantityIndexEntity",
      constraints = "resourceType=? AND index_name=? AND index_value>? AND index_value<?",
    )
  }

  /**
   * Every filter subquery selects `resourceUuid` alone, so the index answers it without a fetch.
   */
  @Test
  fun `reference and uri searches are answered from a covering index`() = runTest {
    for ((name, query) in listOf("reference" to referenceSearch(), "uri" to uriSearch())) {
      val line = planFor(query).first { it.startsWith("SEARCH ") && "IndexEntity" in it }
      assertTrue(line.contains("USING COVERING INDEX"), "$name should be covering, got: $line")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Search shapes
  // ---------------------------------------------------------------------------------------------

  private fun includeQuery(): SearchQuery =
    Search(ResourceType.Observation)
      .apply { include<Patient>(ReferenceClientParam("subject")) }
      .getIncludeQuery(listOf(BASE_UUID_A, BASE_UUID_B))

  private fun revIncludeQuery(): SearchQuery =
    Search(ResourceType.Patient)
      .apply { revInclude<Observation>(ReferenceClientParam("subject")) }
      .getRevIncludeQuery(listOf("Patient/a", "Patient/b"))

  private fun quantitySearch() =
    Search(ResourceType.Observation)
      .apply {
        filter(QuantityClientParam("value-quantity"), { value = BigDecimal.parseString("5.4") })
      }
      .getQuery()

  private fun quantitySearchWithUnit() =
    Search(ResourceType.Observation)
      .apply {
        filter(
          QuantityClientParam("value-quantity"),
          {
            value = BigDecimal.parseString("5.4")
            system = "http://unitsofmeasure.org"
            unit = "g/dL"
          },
        )
      }
      .getQuery()

  private fun referenceSearch() =
    Search(ResourceType.Observation)
      .apply { filter(ReferenceClientParam("subject"), { value = "Patient/patient-1" }) }
      .getQuery()

  private fun uriSearch() =
    Search(ResourceType.Patient)
      .apply { filter(UriClientParam("identifier"), { value = "urn:oid:1.2.3" }) }
      .getQuery()

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

  private fun containsStringSearch() =
    Search(ResourceType.Patient)
      .apply {
        filter(
          StringClientParam("given"),
          {
            value = "an"
            modifier = StringFilterModifier.CONTAINS
          },
        )
      }
      .getQuery()

  // ---------------------------------------------------------------------------------------------
  // Plan helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts [table] is searched through an index constrained by exactly [constraints]. The
   * constraint list is SQLite's own rendering of which columns it could narrow on, which decides
   * whether the index is doing its job.
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
