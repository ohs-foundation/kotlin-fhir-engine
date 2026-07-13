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
package dev.ohs.fhir.engine.impl

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.engine.search.count
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.engine.testStorageDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FhirEngineImplTest {

  /**
   * Initializes [FhirEngineProvider] and returns a [FhirEngine] seeded with [TEST_PATIENT_1].
   *
   * Called at the start of each test's [runTest] instead of from an async `@BeforeTest`: on
   * Kotlin/Wasm `runTest` returns a `Promise` that the test framework does not await for
   * `@BeforeTest`, so the seed would not reliably be applied before the test body runs.
   * `@AfterTest` clears the singleton between tests, so [FhirEngineProvider.init] runs fresh each
   * time.
   */
  private suspend fun setUpEngine(): FhirEngine {
    FhirEngineProvider.init(FhirEngineConfiguration(storageDirectory = testStorageDirectory()))
    return FhirEngineProvider.getInstance().apply {
      clearDatabase()
      create(TEST_PATIENT_1)
    }
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.clearInstance()
  }

  @Test
  fun create_shouldCreateResource() = runTest {
    val fhirEngine = setUpEngine()

    val ids = fhirEngine.create(TEST_PATIENT_2)

    assertEquals(listOf("test_patient_2"), ids)
    val retrieved = fhirEngine.get(ResourceType.Patient, TEST_PATIENT_2_ID)
    assertIs<Patient>(retrieved)
    assertEquals(TEST_PATIENT_2_ID, retrieved.id)
  }

  @Test
  fun createAll_shouldCreateResource() = runTest {
    val fhirEngine = setUpEngine()

    val ids = fhirEngine.create(TEST_PATIENT_1, TEST_PATIENT_2)

    assertEquals(2, ids.size)
    assertTrue(ids.contains("test_patient_1"))
    assertTrue(ids.contains("test_patient_2"))
    val p1 = fhirEngine.get(ResourceType.Patient, TEST_PATIENT_1_ID)
    val p2 = fhirEngine.get(ResourceType.Patient, TEST_PATIENT_2_ID)
    assertIs<Patient>(p1)
    assertIs<Patient>(p2)
  }

  @Test
  fun create_resourceWithoutId_shouldCreateResourceWithAssignedId() = runTest {
    val fhirEngine = setUpEngine()
    val patient =
      Patient(
        name =
          listOf(
            HumanName(
              family = dev.ohs.fhir.model.r4.String(value = "FamilyName"),
              given = listOf(dev.ohs.fhir.model.r4.String(value = "GivenName")),
            ),
          ),
      )

    val ids = fhirEngine.create(patient)

    assertEquals(1, ids.size)
    assertTrue(ids.first().isNotEmpty())
    val retrieved = fhirEngine.get(ResourceType.Patient, ids.first())
    assertIs<Patient>(retrieved)
    assertEquals("FamilyName", (retrieved as Patient).name.first().family?.value)
  }

  @Test
  fun update_nonexistentResource_shouldThrowResourceNotFoundException() = runTest {
    val fhirEngine = setUpEngine()

    assertFailsWith<ResourceNotFoundException> { fhirEngine.update(TEST_PATIENT_2) }
  }

  @Test
  fun update_shouldUpdateResource() = runTest {
    val fhirEngine = setUpEngine()
    val patient1 = Patient(id = "test-update-patient-001")
    val patient2 = Patient(id = "test-update-patient-002")
    fhirEngine.create(patient1, patient2)

    val updatedPatient1 =
      Patient(
        id = "test-update-patient-001",
        name =
          listOf(
            HumanName(family = dev.ohs.fhir.model.r4.String(value = "UpdatedFamily1")),
          ),
      )
    val updatedPatient2 =
      Patient(
        id = "test-update-patient-002",
        name =
          listOf(
            HumanName(family = dev.ohs.fhir.model.r4.String(value = "UpdatedFamily2")),
          ),
      )

    fhirEngine.update(updatedPatient1, updatedPatient2)

    val retrieved1 = fhirEngine.get(ResourceType.Patient, "test-update-patient-001") as Patient
    val retrieved2 = fhirEngine.get(ResourceType.Patient, "test-update-patient-002") as Patient
    assertEquals("UpdatedFamily1", retrieved1.name.first().family?.value)
    assertEquals("UpdatedFamily2", retrieved2.name.first().family?.value)
  }

  @Test
  fun update_existingAndNonExistingResource_shouldNotUpdateAnyResource() = runTest {
    val fhirEngine = setUpEngine()
    val patient1 =
      Patient(
        id = "test-update-patient-001",
        name = listOf(HumanName(family = FhirString(value = "Original"))),
      )
    fhirEngine.create(patient1)

    val updatedPatient1 =
      patient1.copy(name = listOf(HumanName(family = FhirString(value = "Updated"))))
    val nonExistentPatient = Patient(id = "test-update-patient-002")

    assertFailsWith<ResourceNotFoundException> {
      fhirEngine.update(updatedPatient1, nonExistentPatient)
    }

    val retrieved = fhirEngine.get(ResourceType.Patient, "test-update-patient-001") as Patient
    assertEquals("Original", retrieved.name.first().family?.value)
  }

  @Test
  fun update_existingAndNonExistingResource_shouldThrowResourceNotFoundException() = runTest {
    val fhirEngine = setUpEngine()
    val patient1 = Patient(id = "test-update-patient-001")
    fhirEngine.create(patient1)

    val nonExistentPatient = Patient(id = "test-update-patient-002")

    assertFailsWith<ResourceNotFoundException> { fhirEngine.update(patient1, nonExistentPatient) }
  }

  @Test
  fun load_nonexistentResource_shouldThrowResourceNotFoundException() = runTest {
    val fhirEngine = setUpEngine()

    val exception =
      assertFailsWith<ResourceNotFoundException> {
        fhirEngine.get(ResourceType.Patient, "nonexistent_patient")
      }
    assertNotNull(exception.message)
    assertTrue(exception.message!!.contains("Patient"))
    assertTrue(exception.message!!.contains("nonexistent_patient"))
  }

  @Test
  fun load_shouldReturnResource() = runTest {
    val fhirEngine = setUpEngine()

    val result = fhirEngine.get(ResourceType.Patient, TEST_PATIENT_1_ID)

    assertIs<Patient>(result)
    assertEquals(TEST_PATIENT_1_ID, result.id)
  }

  @Test
  fun clearDatabase_shouldClearAllTablesData() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "clear-1"), Patient(id = "clear-2"))
    assertEquals(3, fhirEngine.count<Patient> {})

    fhirEngine.clearDatabase()

    assertEquals(0, fhirEngine.count<Patient> {})
  }

  @Test
  fun delete_shouldRemoveResource() = runTest {
    val fhirEngine = setUpEngine()
    assertIs<Patient>(fhirEngine.get(ResourceType.Patient, TEST_PATIENT_1_ID))

    fhirEngine.delete(ResourceType.Patient, TEST_PATIENT_1_ID)

    assertFailsWith<ResourceNotFoundException> {
      fhirEngine.get(ResourceType.Patient, TEST_PATIENT_1_ID)
    }
    assertEquals(0, fhirEngine.count<Patient> {})
  }

  @Test
  fun delete_nonexistentResource_isNoOp() = runTest {
    val fhirEngine = setUpEngine()

    fhirEngine.delete(ResourceType.Patient, "does-not-exist")

    assertIs<Patient>(fhirEngine.get(ResourceType.Patient, TEST_PATIENT_1_ID))
  }

  @Test
  fun crud_fullCycle_createReadUpdateDelete() = runTest {
    val fhirEngine = setUpEngine()
    val id = "crud-cycle-1"

    fhirEngine.create(Patient(id = id, name = listOf(HumanName(family = FhirString(value = "A")))))
    assertEquals(
      "A",
      (fhirEngine.get(ResourceType.Patient, id) as Patient).name.first().family?.value,
    )

    fhirEngine.update(Patient(id = id, name = listOf(HumanName(family = FhirString(value = "B")))))
    assertEquals(
      "B",
      (fhirEngine.get(ResourceType.Patient, id) as Patient).name.first().family?.value,
    )

    fhirEngine.delete(ResourceType.Patient, id)
    assertFailsWith<ResourceNotFoundException> { fhirEngine.get(ResourceType.Patient, id) }
  }

  @Test
  fun search_xFhirQueryString_filtersById_andLimitsByCount() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "xq1"), Patient(id = "xq2"), Patient(id = "xq3"))

    val byId = fhirEngine.search("Patient?_id=xq2")
    assertEquals(1, byId.size)
    assertEquals("xq2", (byId.first().resource as Patient).id)

    val limited = fhirEngine.search("Patient?_count=2")
    assertEquals(2, limited.size)
  }

  @Test
  fun search_xFhirQueryString_unrecognizedParam_throwsIllegalArgumentException() = runTest {
    val fhirEngine = setUpEngine()
    val exception =
      assertFailsWith<IllegalArgumentException> {
        fhirEngine.search("Patient?customParam=true&gender=male&_sort=name")
      }
    assertEquals("customParam not found in Patient", exception.message)
  }

  @Test
  fun getLocalChanges_shouldReturnSingleLocalChange() = runTest {
    val fhirEngine = setUpEngine()
    val patient = Patient(id = "lc-1")
    fhirEngine.create(patient)

    val changes = fhirEngine.getLocalChanges(ResourceType.Patient, "lc-1")

    assertEquals(1, changes.size)
    assertEquals("lc-1", changes.first().resourceId)
    assertEquals(ResourceType.Patient.name, changes.first().resourceType)
    assertEquals(LocalChange.Type.INSERT, changes.first().type)
  }

  @Test
  fun getLocalChanges_shouldReturnAllLocalChanges() = runTest {
    val fhirEngine = setUpEngine()
    val patient = Patient(id = "lc-all")
    fhirEngine.create(patient)
    fhirEngine.update(
      Patient(id = "lc-all", name = listOf(HumanName(family = FhirString(value = "One")))),
    )
    fhirEngine.update(
      Patient(id = "lc-all", name = listOf(HumanName(family = FhirString(value = "Two")))),
    )

    val changes = fhirEngine.getLocalChanges(ResourceType.Patient, "lc-all")

    assertEquals(3, changes.size)
    assertTrue(changes.all { it.resourceId == "lc-all" })
    assertTrue(changes.all { it.resourceType == ResourceType.Patient.name })
    assertEquals(LocalChange.Type.INSERT, changes[0].type)
    assertEquals(LocalChange.Type.UPDATE, changes[1].type)
    assertEquals(LocalChange.Type.UPDATE, changes[2].type)
  }

  @Test
  fun getLocalChanges_wrongResourceId_shouldReturnEmpty() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "lc-present"))

    assertTrue(fhirEngine.getLocalChanges(ResourceType.Patient, "nonexistent_patient").isEmpty())
  }

  @Test
  fun getLocalChanges_wrongResourceType_shouldReturnEmpty() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "lc-type"))

    assertTrue(fhirEngine.getLocalChanges(ResourceType.Encounter, "lc-type").isEmpty())
  }

  @Test
  fun purge_withLocalChangeAndForcePurgeTrue_shouldPurgeResource() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.purge(ResourceType.Patient, TEST_PATIENT_1_ID, true)

    assertFailsWith<ResourceNotFoundException> {
      fhirEngine.get(ResourceType.Patient, TEST_PATIENT_1_ID)
    }
    assertTrue(fhirEngine.getLocalChanges(ResourceType.Patient, TEST_PATIENT_1_ID).isEmpty())
  }

  @Test
  fun purge_multipleWithLocalChangeAndForcePurgeTrue_shouldPurgeResources() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "purge-a"), Patient(id = "purge-b"))

    fhirEngine.purge(ResourceType.Patient, setOf("purge-a", "purge-b"), true)

    assertFailsWith<ResourceNotFoundException> { fhirEngine.get(ResourceType.Patient, "purge-a") }
    assertFailsWith<ResourceNotFoundException> { fhirEngine.get(ResourceType.Patient, "purge-b") }
    assertTrue(fhirEngine.getLocalChanges(ResourceType.Patient, "purge-a").isEmpty())
    assertTrue(fhirEngine.getLocalChanges(ResourceType.Patient, "purge-b").isEmpty())
  }

  @Test
  fun purge_withLocalChangeAndForcePurgeFalse_shouldThrowIllegalStateException() = runTest {
    val fhirEngine = setUpEngine()
    val exception =
      assertFailsWith<IllegalStateException> {
        fhirEngine.purge(ResourceType.Patient, TEST_PATIENT_1_ID)
      }
    assertTrue(exception.message!!.contains("has local changes"))
  }

  @Test
  fun purge_resourceNotAvailable_shouldThrowResourceNotFoundException() = runTest {
    val fhirEngine = setUpEngine()
    val exception =
      assertFailsWith<ResourceNotFoundException> {
        fhirEngine.purge(ResourceType.Patient, "nonexistent_patient")
      }
    assertTrue(exception.message!!.contains("nonexistent_patient"))
  }

  @Test
  fun withTransaction_savesChangesSuccessfully() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.withTransaction {
      create(Patient(id = "txn-1", name = listOf(HumanName(family = FhirString(value = "A")))))
      create(Patient(id = "txn-2", name = listOf(HumanName(family = FhirString(value = "B")))))
    }

    assertEquals("A", fhirEngine.get<Patient>("txn-1").name.first().family?.value)
    assertEquals("B", fhirEngine.get<Patient>("txn-2").name.first().family?.value)
  }

  @Test
  fun withTransaction_rollsBackChangesWhenErrorOccurs() = runTest {
    val fhirEngine = setUpEngine()
    try {
      fhirEngine.withTransaction {
        create(Patient(id = "txn-rollback"))
        // An exception will rollback the entire block
        get(ResourceType.Patient, "non_existent_id")
      }
    } catch (_: ResourceNotFoundException) {}

    assertFailsWith<ResourceNotFoundException> {
      fhirEngine.get(ResourceType.Patient, "txn-rollback")
    }
  }

  companion object {
    private const val TEST_PATIENT_1_ID = "test_patient_1"
    private val TEST_PATIENT_1 = Patient(id = TEST_PATIENT_1_ID)

    private const val TEST_PATIENT_2_ID = "test_patient_2"
    private val TEST_PATIENT_2 = Patient(id = TEST_PATIENT_2_ID)
  }
}
