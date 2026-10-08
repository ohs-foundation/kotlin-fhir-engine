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
package dev.ohs.fhir.engine.sync.upload

import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.db.impl.DatabaseConfig
import dev.ohs.fhir.engine.db.impl.DatabaseImpl
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Instant as FhirInstant
import dev.ohs.fhir.model.r4.Meta
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpPostResourceConsolidatorTest {
  private lateinit var database: Database
  private lateinit var resourceConsolidator: ResourceConsolidator

  @BeforeTest
  fun setUp() {
    database =
      DatabaseImpl(
        testPlatformContext(),
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        testStorageDirectory(),
        DatabaseConfig(inMemory = true),
      )
    resourceConsolidator = HttpPostResourceConsolidator(database)
  }

  @AfterTest
  fun tearDown() {
    database.close()
  }

  @Test
  fun consolidate_resourceResponse_replacesTheResourceId() = runTest {
    database.insert(PRE_SYNC_PATIENT)
    val localChanges = database.getLocalChanges(ResourceType.Patient, PRE_SYNC_PATIENT.id!!)

    resourceConsolidator.consolidate(
      UploadRequestResult.Success(
        listOf(ResourceUploadResponseMapping(localChanges, POST_SYNC_PATIENT)),
      ),
    )

    assertEquals(POST_SYNC_PATIENT.id, database.select(ResourceType.Patient, "patient2").id)
    val exception =
      assertFailsWith<ResourceNotFoundException> {
        database.select(ResourceType.Patient, "patient1")
      }
    assertEquals("Resource not found with type Patient and id patient1!", exception.message)
  }

  @Test
  fun consolidate_resourceResponse_updatesReferencesInReferencingResources() = runTest {
    database.insert(PRE_SYNC_PATIENT, OBSERVATION)
    val localChanges = database.getLocalChanges(ResourceType.Patient, PRE_SYNC_PATIENT.id!!)

    resourceConsolidator.consolidate(
      UploadRequestResult.Success(
        listOf(ResourceUploadResponseMapping(localChanges, POST_SYNC_PATIENT)),
      ),
    )

    val observation = database.select(ResourceType.Observation, "observation1") as Observation
    assertEquals("Patient/patient2", observation.subject?.reference?.value)
  }

  @Test
  fun consolidate_resourceResponse_updatesReferencesInLocalChangesOfReferencingResources() =
    runTest {
      database.insert(PRE_SYNC_PATIENT, OBSERVATION)
      val localChanges = database.getLocalChanges(ResourceType.Patient, PRE_SYNC_PATIENT.id!!)

      resourceConsolidator.consolidate(
        UploadRequestResult.Success(
          listOf(ResourceUploadResponseMapping(localChanges, POST_SYNC_PATIENT)),
        ),
      )

      val localChange = database.getLocalChanges(ResourceType.Observation, "observation1").last()
      val observation = fhirJsonParser.decodeFromString<Observation>(localChange.payload)
      assertEquals("Patient/patient2", observation.subject?.reference?.value)
    }

  @Test
  fun consolidate_bundleComponentResponse_replacesTheResourceId() = runTest {
    database.insert(PRE_SYNC_PATIENT)
    val localChanges = database.getLocalChanges(ResourceType.Patient, PRE_SYNC_PATIENT.id!!)
    val patientResponse = responseBundle("Patient/patient2/_history/1").entry[0].response!!

    resourceConsolidator.consolidate(
      UploadRequestResult.Success(
        listOf(BundleComponentUploadResponseMapping(localChanges, patientResponse)),
      ),
    )

    assertEquals("patient2", database.select(ResourceType.Patient, "patient2").id)
    val exception =
      assertFailsWith<ResourceNotFoundException> {
        database.select(ResourceType.Patient, "patient1")
      }
    assertEquals("Resource not found with type Patient and id patient1!", exception.message)
  }

  @Test
  fun consolidate_bundleComponentResponse_updatesReferencesInReferencingResources() = runTest {
    database.insert(PRE_SYNC_PATIENT, OBSERVATION)
    val patientLocalChanges = database.getLocalChanges(ResourceType.Patient, PRE_SYNC_PATIENT.id!!)
    val observationLocalChanges =
      database.getLocalChanges(ResourceType.Observation, OBSERVATION.id!!)
    val bundle =
      responseBundle("Patient/patient2/_history/1", "Observation/observation2/_history/1")

    resourceConsolidator.consolidate(
      UploadRequestResult.Success(
        listOf(
          BundleComponentUploadResponseMapping(patientLocalChanges, bundle.entry[0].response!!),
          BundleComponentUploadResponseMapping(
            observationLocalChanges,
            bundle.entry[1].response!!,
          ),
        ),
      ),
    )

    val observation = database.select(ResourceType.Observation, "observation2") as Observation
    assertEquals("Patient/patient2", observation.subject?.reference?.value)
  }

  @Test
  fun consolidate_bundleComponentResponse_discardsTheLocalChanges() = runTest {
    database.insert(PRE_SYNC_PATIENT)
    val localChanges = database.getLocalChanges(ResourceType.Patient, PRE_SYNC_PATIENT.id!!)
    val patientResponse = responseBundle("Patient/patient2/_history/1").entry[0].response!!

    resourceConsolidator.consolidate(
      UploadRequestResult.Success(
        listOf(BundleComponentUploadResponseMapping(localChanges, patientResponse)),
      ),
    )

    assertTrue(database.getAllLocalChanges().isEmpty())
  }

  private fun responseBundle(vararg locations: String): Bundle {
    val entries =
      locations.joinToString(",") {
        """
        {
          "response": {
            "status": "201 Created",
            "location": "$it",
            "etag": "1",
            "lastModified": "2024-04-08T11:15:42.648+00:00",
            "outcome": { "resourceType": "OperationOutcome" }
          }
        }
        """
      }
    return fhirJsonParser.decodeFromString<Bundle>(
      """{"resourceType":"Bundle","id":"bundle1","type":"transaction-response","entry":[$entries]}""",
    )
  }

  private companion object {
    val PRE_SYNC_PATIENT = Patient(id = "patient1")
    val POST_SYNC_PATIENT =
      Patient(
        id = "patient2",
        meta =
          Meta(
            versionId = Id(value = "1"),
            lastUpdated =
              FhirInstant(value = FhirDateTime.fromString("2024-04-08T11:15:42.648+00:00")),
          ),
      )
    val OBSERVATION =
      Observation(
        id = "observation1",
        status = dev.ohs.fhir.model.r4.Enumeration(value = Observation.ObservationStatus.Final),
        code = dev.ohs.fhir.model.r4.CodeableConcept(),
        subject = Reference(reference = FhirString(value = "Patient/patient1")),
      )
  }
}
