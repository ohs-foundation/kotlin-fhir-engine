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
package dev.ohs.fhir.db.impl

import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.db.Database
import dev.ohs.fhir.db.ResourceNotFoundException
import dev.ohs.fhir.index.ResourceIndexer
import dev.ohs.fhir.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Lives in desktopTest (constructs [DatabaseImpl] directly, which needs a platform DB). Only the
 * indexer-independent DB-layer tests are ported here:
 * - core CRUD + local-change + purge + counts + remote-insert meta
 * - the reference-cascade (`updateResourceAndReferences`) — the DB's unique non-trivial logic.
 *
 * Not ported (covered elsewhere / blocked / N/A):
 * - ~80 `search_*` execution tests — query generation is covered by SearchTest; execution for
 *   quantity/date/gender is blocked by the indexer (kotlin-fhir-path) limitations.
 * - migration / encryption tests — N/A in KMP.
 *
 * KMP adaptations: HAPI types → kotlin-fhir; assertResourceEquals → compare id/gender or serialized
 * form; `LocalChange.Type` enum at the Database layer; in-file DB cleared per test.
 */
class DatabaseImplTest {
  private lateinit var database: Database

  @BeforeTest
  fun setUp() = runTest {
    database = DatabaseImpl(Unit, ResourceIndexer(SearchParamDefinitionsProviderImpl()))
    database.clearDatabase()
    database.insert(TEST_PATIENT_1)
  }

  @AfterTest
  fun tearDown() = runTest {
    database.clearDatabase()
    database.close()
  }

  @Test
  fun insert_shouldInsertResource() = runTest {
    database.insert(TEST_PATIENT_2)
    val selected = database.select(ResourceType.Patient, TEST_PATIENT_2_ID) as Patient
    assertEquals(TEST_PATIENT_2_ID, selected.id)
  }

  @Test
  fun insertAll_shouldInsertResources() = runTest {
    val p2 = Patient(id = "p-a")
    val p3 = Patient(id = "p-b")
    database.insert(p2, p3)
    assertEquals("p-a", (database.select(ResourceType.Patient, "p-a") as Patient).id)
    assertEquals("p-b", (database.select(ResourceType.Patient, "p-b") as Patient).id)
  }

  @Test
  fun select_shouldReturnResource() = runTest {
    val selected = database.select(ResourceType.Patient, TEST_PATIENT_1_ID) as Patient
    assertEquals(TEST_PATIENT_1_ID, selected.id)
  }

  @Test
  fun select_nonexistentResource_shouldThrowResourceNotFoundException() = runTest {
    assertFailsWith<ResourceNotFoundException> {
      database.select(ResourceType.Patient, "nonexistent")
    }
  }

  @Test
  fun update_existentResource_shouldUpdateResource() = runTest {
    val updated =
      Patient(id = TEST_PATIENT_1_ID, gender = Enumeration(value = AdministrativeGender.Female))
    database.update(updated)
    val selected = database.select(ResourceType.Patient, TEST_PATIENT_1_ID) as Patient
    assertEquals(AdministrativeGender.Female, selected.gender?.value)
  }

  @Test
  fun update_nonExistingResource_shouldThrowResourceNotFoundException() = runTest {
    // engine-kmp's update throws for a missing resource (vs the engine's silent no-op); this
    // matches
    // FhirEngineImplTest.update_nonexistentResource_shouldThrowResourceNotFoundException.
    assertFailsWith<ResourceNotFoundException> { database.update(Patient(id = "ghost")) }
    assertFailsWith<ResourceNotFoundException> { database.select(ResourceType.Patient, "ghost") }
  }

  @Test
  fun insert_shouldAddInsertLocalChange() = runTest {
    database.insert(TEST_PATIENT_2)
    val changes = database.getAllLocalChanges().filter { it.resourceId == TEST_PATIENT_2_ID }
    assertEquals(1, changes.size)
    with(changes[0]) {
      assertEquals(LocalChange.Type.INSERT, type)
      assertEquals(TEST_PATIENT_2_ID, resourceId)
      assertEquals(ResourceType.Patient.name, resourceType)
      assertEquals(fhirJsonParser.encodeToString(TEST_PATIENT_2), payload)
    }
  }

  @Test
  fun insert_remoteResource_shouldNotInsertLocalChange() = runTest {
    database.insertRemote(Patient(id = "remote-1"))
    assertTrue(database.getAllLocalChanges().none { it.resourceId == "remote-1" })
  }

  @Test
  fun delete_shouldAddDeleteLocalChange() = runTest {
    database.delete(ResourceType.Patient, TEST_PATIENT_1_ID)
    val changes = database.getAllLocalChanges().filter { it.resourceId == TEST_PATIENT_1_ID }
    assertTrue(changes.any { it.type == LocalChange.Type.DELETE })
  }

  @Test
  fun delete_nonExistent_shouldNotInsertLocalChange() = runTest {
    database.delete(ResourceType.Patient, "ghost")
    assertTrue(database.getAllLocalChanges().none { it.resourceId == "ghost" })
  }

  @Test
  fun getLocalChangesCount_oneLocalChange_returnsOne() = runTest {
    // Only TEST_PATIENT_1's INSERT exists from setUp.
    assertEquals(1, database.getLocalChangesCount())
  }

  @Test
  fun clearDatabase_shouldClearAllTablesData() = runTest {
    assertEquals(
      TEST_PATIENT_1_ID,
      (database.select(ResourceType.Patient, TEST_PATIENT_1_ID) as Patient).id,
    )
    database.clearDatabase()
    assertFailsWith<ResourceNotFoundException> {
      database.select(ResourceType.Patient, TEST_PATIENT_1_ID)
    }
    assertEquals(0, database.getLocalChangesCount())
  }

  @Test
  fun purge_withLocalChangeAndForcePurgeTrue_shouldPurgeResource() = runTest {
    database.purge(ResourceType.Patient, setOf(TEST_PATIENT_1_ID), forcePurge = true)
    assertFailsWith<ResourceNotFoundException> {
      database.select(ResourceType.Patient, TEST_PATIENT_1_ID)
    }
    assertTrue(database.getLocalChanges(ResourceType.Patient, TEST_PATIENT_1_ID).isEmpty())
  }

  @Test
  fun purge_withLocalChangeAndForcePurgeFalse_shouldThrowIllegalStateException() = runTest {
    val exception =
      assertFailsWith<IllegalStateException> {
        database.purge(ResourceType.Patient, setOf(TEST_PATIENT_1_ID), forcePurge = false)
      }
    assertTrue(exception.message!!.contains("has local changes"))
  }

  @Test
  fun purge_resourceNotAvailable_shouldThrowResourceNotFoundException() = runTest {
    assertFailsWith<ResourceNotFoundException> {
      database.purge(ResourceType.Patient, setOf("nonexistent"), forcePurge = true)
    }
  }

  @Test
  fun updateResourceAndReferences_shouldUpdateReferencesInReferringResource() = runTest {
    // A locally-created Observation references the Patient by its (local) id.
    val observation =
      Observation(
        id = "obs-ref",
        status = Enumeration(value = Observation.ObservationStatus.Final),
        code = dev.ohs.fhir.model.r4.CodeableConcept(),
        subject = Reference(reference = FhirString(value = "Patient/$TEST_PATIENT_1_ID")),
      )
    database.insert(observation)

    // Change the Patient's id; the reference in the Observation's local change must be rewritten.
    val updatedPatient = Patient(id = "synced-patient-1")
    database.updateResourceAndReferences(TEST_PATIENT_1_ID, updatedPatient)

    val obsChanges = database.getLocalChanges(ResourceType.Observation, "obs-ref")
    assertTrue(obsChanges.isNotEmpty())
    assertTrue(
      obsChanges.first().payload.contains("Patient/synced-patient-1"),
      "Expected referring Observation local change to point at the new patient id, was: " +
        obsChanges.first().payload,
    )
  }

  companion object {
    private const val TEST_PATIENT_1_ID = "test_patient_1"
    private val TEST_PATIENT_1 =
      Patient(id = TEST_PATIENT_1_ID, gender = Enumeration(value = AdministrativeGender.Male))

    private const val TEST_PATIENT_2_ID = "test_patient_2"
    private val TEST_PATIENT_2 =
      Patient(id = TEST_PATIENT_2_ID, gender = Enumeration(value = AdministrativeGender.Male))
  }
}
