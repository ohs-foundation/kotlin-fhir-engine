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
package dev.ohs.fhir.engine.search

import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Executes string searches against a real database, rather than asserting the SQL they compile to.
 *
 * `StringIndexEntity.index_value` is stored NOCASE so the prefix search can use its index, and
 * `:exact` compensates with an explicit `COLLATE BINARY`. Both halves are matching semantics, and
 * `SearchTest` only compares generated SQL — so without these tests the collation could be changed
 * in either direction and every test would still pass while search quietly returned the wrong rows.
 */
class StringSearchMatchingTest {

  @BeforeTest
  fun setUp() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    FhirEngineProvider.getInstance()
      .create(
        patient("string-search-lower", family = "okonkwo"),
        patient("string-search-title", family = "Okonkwo"),
        patient("string-search-upper", family = "OKONKWO"),
        patient("string-search-other", family = "Achebe"),
      )
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.reset()
  }

  @Test
  fun `prefix search matches regardless of case`() = runTest {
    val ids = searchFamily("okon", StringFilterModifier.STARTS_WITH)

    assertEquals(
      listOf("string-search-lower", "string-search-title", "string-search-upper").sorted(),
      ids,
    )
  }

  @Test
  fun `prefix search still excludes non-matching values`() = runTest {
    assertEquals(
      listOf("string-search-other"),
      searchFamily("ache", StringFilterModifier.STARTS_WITH),
    )
  }

  @Test
  fun `contains search matches regardless of case`() = runTest {
    val ids = searchFamily("KONKW", StringFilterModifier.CONTAINS)

    assertEquals(
      listOf("string-search-lower", "string-search-title", "string-search-upper").sorted(),
      ids,
    )
  }

  @Test
  fun `exact search is case sensitive`() = runTest {
    assertEquals(
      listOf("string-search-title"),
      searchFamily("Okonkwo", StringFilterModifier.MATCHES_EXACTLY),
    )
    assertEquals(
      listOf("string-search-lower"),
      searchFamily("okonkwo", StringFilterModifier.MATCHES_EXACTLY),
    )
  }

  @Test
  fun `exact search returns nothing when only the case differs`() = runTest {
    assertEquals(emptyList(), searchFamily("OkOnKwO", StringFilterModifier.MATCHES_EXACTLY))
  }

  private suspend fun searchFamily(value: String, modifier: StringFilterModifier): List<String> =
    FhirEngineProvider.getInstance()
      .search<Patient> {
        filter(
          StringClientParam("family"),
          {
            this.value = value
            this.modifier = modifier
          },
        )
      }
      .mapNotNull { it.resource.id }
      .sorted()

  private fun patient(id: String, family: String) =
    Patient(id = id, name = listOf(HumanName(family = FhirString(value = family))))
}
