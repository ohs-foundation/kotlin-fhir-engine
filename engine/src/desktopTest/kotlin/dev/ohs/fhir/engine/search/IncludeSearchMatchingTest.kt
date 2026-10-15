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
import dev.ohs.fhir.engine.resourceType
import dev.ohs.fhir.engine.search.filter.TokenFilterValue
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Executes `_include` and `_revinclude` searches against a real database, rather than asserting the
 * SQL they compile to.
 *
 * The include join is written for the query planner rather than for legibility — see
 * `Search.getIncludeQuery` — and `SearchTest` only compares generated SQL while
 * `SearchQueryPlanTest` only reads the plan. Neither would notice a join that matched the wrong
 * rows, or none.
 *
 * The exclusion cases carry most of the weight: a join that matches too much satisfies any test
 * that only asks whether the referenced resource came back.
 */
class IncludeSearchMatchingTest {

  @BeforeTest
  fun setUp() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    FhirEngineProvider.getInstance()
      .create(
        patient(SUBJECT_ID),
        // Referenced by nothing, so an over-matching join pulls it into a result.
        patient(UNREFERENCED_ID),
        // One logical id across two types: only the type half of the match separates them.
        patient(SHARED_ID),
        practitioner(SHARED_ID),
        observation(OBSERVATION_ID, subject = "Patient/$SUBJECT_ID"),
        observation(SHARED_OBSERVATION_ID, subject = "Practitioner/$SHARED_ID"),
      )
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.clearInstance()
  }

  @Test
  fun `include returns the referenced resource`() = runTest {
    val results =
      FhirEngineProvider.getInstance().search<Observation> {
        filter(TokenClientParam(CODE_PARAM), { value = TokenFilterValue.string(CODE) })
        include<Patient>(ReferenceClientParam(SUBJECT_PARAM))
      }

    val observation = results.single { it.resource.id == OBSERVATION_ID }
    assertEquals(
      listOf(SUBJECT_ID),
      observation.included?.get(SUBJECT_PARAM)?.map { it.id },
    )
  }

  @Test
  fun `include returns nothing for a resource that references no patient`() = runTest {
    val results =
      FhirEngineProvider.getInstance().search<Observation> {
        filter(TokenClientParam(CODE_PARAM), { value = TokenFilterValue.string(CODE) })
        include<Patient>(ReferenceClientParam(SUBJECT_PARAM))
      }

    // This observation's subject is a Practitioner, so including patients must find none for it.
    val other = results.single { it.resource.id == SHARED_OBSERVATION_ID }
    assertEquals(emptyList(), other.included?.get(SUBJECT_PARAM)?.map { it.id } ?: emptyList())
  }

  @Test
  fun `include matches the referenced type rather than the id alone`() = runTest {
    val results =
      FhirEngineProvider.getInstance().search<Observation> {
        filter(TokenClientParam(CODE_PARAM), { value = TokenFilterValue.string(CODE) })
        include<Practitioner>(ReferenceClientParam(SUBJECT_PARAM))
      }

    val observation = results.single { it.resource.id == SHARED_OBSERVATION_ID }
    val included = observation.included?.get(SUBJECT_PARAM).orEmpty()

    // A patient shares this id; only the practitioner may come back, and only once.
    assertEquals(listOf(SHARED_ID), included.map { it.id })
    assertEquals(listOf("Practitioner"), included.map { it.resourceType })
  }

  @Test
  fun `revinclude returns the resources that reference the match`() = runTest {
    val results =
      FhirEngineProvider.getInstance().search<Patient> {
        filter(StringClientParam(FAMILY_PARAM), { value = FAMILY })
        revInclude<Observation>(ReferenceClientParam(SUBJECT_PARAM))
      }

    val subject = results.single { it.resource.id == SUBJECT_ID }
    val revIncluded = subject.revIncluded.orEmpty().values.flatten().map { it.id }
    assertEquals(listOf(OBSERVATION_ID), revIncluded)

    // The unreferenced patient matches the same filter and must carry nothing.
    val unreferenced = results.single { it.resource.id == UNREFERENCED_ID }
    assertEquals(emptyList(), unreferenced.revIncluded.orEmpty().values.flatten().map { it.id })
  }

  private fun patient(id: String) =
    Patient(id = id, name = listOf(HumanName(family = FhirString(value = FAMILY))))

  private fun practitioner(id: String) =
    Practitioner(id = id, name = listOf(HumanName(family = FhirString(value = FAMILY))))

  private fun observation(id: String, subject: String) =
    Observation(
      id = id,
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code =
        CodeableConcept(
          coding = listOf(Coding(system = Uri(value = SYSTEM), code = Code(value = CODE))),
        ),
      subject = Reference(reference = FhirString(value = subject)),
    )

  private companion object {
    const val SUBJECT_ID = "include-subject"
    const val UNREFERENCED_ID = "include-unreferenced"
    const val SHARED_ID = "include-shared-id"
    const val OBSERVATION_ID = "include-observation"
    const val SHARED_OBSERVATION_ID = "include-observation-practitioner"

    const val FAMILY = "Okonkwo"
    const val SYSTEM = "http://loinc.org"
    const val CODE = "718-7"

    const val SUBJECT_PARAM = "subject"
    const val CODE_PARAM = "code"
    const val FAMILY_PARAM = "family"
  }
}
