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
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.String as FhirString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Executes sorted searches against a real database, rather than asserting the SQL they compile to.
 *
 * A resource can hold several values for the sort parameter. It sorts by its smallest when
 * ascending and its largest when descending, and one with no value sorts last either way. `a` has
 * its smallest name last and `b` has it first, so a sort that reads whichever row comes to hand
 * misorders at least one of them. `d` has neither a name nor a birth date.
 *
 * An unfiltered sort reads resources with a value and those without in separate queries, so the
 * paging tests cross that boundary. A filtered sort runs as one query.
 */
class SortMatchingTest {

  @BeforeTest
  fun setUp() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    FhirEngineProvider.getInstance()
      .create(
        patient("a", "1990-01-01", "Zed", "Abe"),
        patient("b", "2000-01-01", "Bob", "Yan"),
        patient("c", "2010-01-01", "Dan"),
        patient("d", birthDate = null),
        observation("o1", "2020-01-01T00:00:00Z"),
        observation("o2", "2010-01-01T00:00:00Z"),
        observation("o3", effective = null),
      )
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.resetForTesting()
  }

  @Test
  fun `ascending sort orders each patient by its smallest name`() = runTest {
    assertEquals(listOf("a", "b", "c", "d"), sortByGiven(Order.ASCENDING))
  }

  @Test
  fun `descending sort orders each patient by its largest name`() = runTest {
    assertEquals(listOf("a", "b", "c", "d"), sortByGiven(Order.DESCENDING))
  }

  @Test
  fun `ascending date sort puts a patient with no birth date last`() = runTest {
    assertEquals(listOf("a", "b", "c", "d"), sortByBirthDate(Order.ASCENDING))
  }

  @Test
  fun `descending date sort puts a patient with no birth date last`() = runTest {
    assertEquals(listOf("c", "b", "a", "d"), sortByBirthDate(Order.DESCENDING))
  }

  @Test
  fun `a page can span patients with a name and without one`() = runTest {
    assertEquals(listOf("c", "d"), sortByGiven(Order.ASCENDING, count = 2, from = 2))
  }

  @Test
  fun `a page past every named patient lists those without a name`() = runTest {
    assertEquals(listOf("d"), sortByGiven(Order.ASCENDING, count = 2, from = 3))
  }

  @Test
  fun `a descending page starts at its offset`() = runTest {
    assertEquals(listOf("b", "c"), sortByGiven(Order.DESCENDING, count = 2, from = 1))
  }

  @Test
  fun `a page past the end is empty`() = runTest {
    assertEquals(emptyList(), sortByGiven(Order.ASCENDING, count = 2, from = 10))
  }

  @Test
  fun `a count of zero is empty`() = runTest {
    assertEquals(emptyList(), sortByGiven(Order.ASCENDING, count = 0))
  }

  @Test
  fun `a filtered sort orders the matches`() = runTest {
    val ids =
      FhirEngineProvider.getInstance()
        .search<Patient> {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("1995-01-01")!!)
              prefix = SearchComparator.Gt
            },
          )
          sort(StringClientParam("given"), Order.DESCENDING)
        }
        .mapNotNull { it.resource.id }

    assertEquals(listOf("b", "c"), ids)
  }

  @Test
  fun `a filtered ascending string sort puts a match with no value last`() = runTest {
    FhirEngineProvider.getInstance().create(patient("e", "2005-01-01"))
    val ids =
      FhirEngineProvider.getInstance()
        .search<Patient> {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("1995-01-01")!!)
              prefix = SearchComparator.Gt
            },
          )
          sort(StringClientParam("given"), Order.ASCENDING)
        }
        .mapNotNull { it.resource.id }

    assertEquals(listOf("b", "c", "e"), ids)
  }

  @Test
  fun `a dateTime sort puts a resource with no value last`() = runTest {
    assertEquals(listOf("o2", "o1", "o3"), sortObservationsByDate(Order.ASCENDING))
    assertEquals(listOf("o1", "o2", "o3"), sortObservationsByDate(Order.DESCENDING))
  }

  private suspend fun sortByGiven(order: Order, count: Int? = null, from: Int? = null) =
    FhirEngineProvider.getInstance()
      .search<Patient> {
        sort(StringClientParam("given"), order)
        this.count = count
        this.from = from
      }
      .mapNotNull { it.resource.id }

  private suspend fun sortObservationsByDate(order: Order): List<String> =
    FhirEngineProvider.getInstance()
      .search<Observation> { sort(DateClientParam("date"), order) }
      .mapNotNull { it.resource.id }

  private suspend fun sortByBirthDate(order: Order): List<String> =
    FhirEngineProvider.getInstance()
      .search<Patient> { sort(DateClientParam("birthdate"), order) }
      .mapNotNull { it.resource.id }

  private fun observation(id: String, effective: String?) =
    Observation(
      id = id,
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code = CodeableConcept(),
      effective =
        effective?.let {
          Observation.Effective.DateTime(DateTime(value = FhirDateTime.fromString(it)))
        },
    )

  private fun patient(id: String, birthDate: String?, vararg given: String) =
    Patient(
      id = id,
      birthDate = birthDate?.let { Date(value = FhirDate.fromString(it)) },
      name =
        if (given.isEmpty()) {
          emptyList()
        } else {
          listOf(HumanName(given = given.map { FhirString(value = it) }))
        },
    )
}
