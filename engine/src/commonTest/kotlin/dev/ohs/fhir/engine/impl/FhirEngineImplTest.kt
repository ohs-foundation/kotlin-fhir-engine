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
import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.db.impl.DatabaseConfig
import dev.ohs.fhir.engine.db.impl.DatabaseImpl
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.search.DateClientParam
import dev.ohs.fhir.engine.search.LOCAL_LAST_UPDATED
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.count
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.engine.sync.AcceptLocalConflictResolver
import dev.ohs.fhir.engine.sync.ResourceSyncException
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.ResourceUploadResponseMapping
import dev.ohs.fhir.engine.sync.upload.SyncUploadProgress
import dev.ohs.fhir.engine.sync.upload.UploadRequestResult
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Instant as FhirInstant
import dev.ohs.fhir.model.r4.Meta
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class FhirEngineImplTest {

  /**
   * An engine over its own in-memory database seeded with [TEST_PATIENT_1], closed in [tearDown].
   * Each test opens its engine inside [runTest] rather than in an async `@BeforeTest`, which
   * Kotlin/Wasm does not await. In-memory databases are the only ones tests can close on web, an
   * OPFS file reopened after close hangs the browser.
   */
  private suspend fun setUpEngine(): FhirEngine = setUpEngineWithDatabase().first

  private val databases = mutableListOf<Database>()

  @AfterTest
  fun tearDown() {
    databases.forEach { it.close() }
    databases.clear()
  }

  @Test
  fun search_include_returnsReferencedResources() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(
      Practitioner(id = "gp-1"),
      Patient(
        id = "patient-with-gp",
        generalPractitioner =
          listOf(Reference(reference = FhirString(value = "Practitioner/gp-1"))),
      ),
    )

    val results =
      fhirEngine.search<Patient> {
        include<Practitioner>(ReferenceClientParam("general-practitioner"))
      }

    val withGp = results.single { it.resource.id == "patient-with-gp" }
    assertEquals(listOf("gp-1"), withGp.included?.get("general-practitioner")?.map { it.id })
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

  @Test
  fun search_xFhirQuery_genderParam_returnsMatchingPatients() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(
      buildPatient("3", "C", AdministrativeGender.Female),
      buildPatient("2", "B", AdministrativeGender.Female),
      buildPatient("1", "A", AdministrativeGender.Male),
    )

    val result = fhirEngine.search("Patient?gender=female")

    assertEquals(2, result.size)
    assertTrue(
      result.all { (it.resource as Patient).gender?.value == AdministrativeGender.Female },
    )
  }

  @Test
  fun search_xFhirQuery_sortParam_returnsSortedPatients() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(
      buildPatient("3", "C", AdministrativeGender.Female),
      buildPatient("2", "B", AdministrativeGender.Female),
      buildPatient("1", "A", AdministrativeGender.Male),
    )

    val result = fhirEngine.search("Patient?_sort=-name").map { it.resource as Patient }

    assertEquals(
      listOf("C", "B", "A"),
      result.mapNotNull { it.name.firstOrNull()?.given?.firstOrNull()?.value },
    )
  }

  @Test
  fun search_xFhirQuery_noParams_returnsAllPatients() = runTest {
    val fhirEngine = setUpEngine()

    assertEquals(1, fhirEngine.search("Patient").size)
  }

  @Test
  fun search_xFhirQuery_unrecognizedResourceType_throws() = runTest {
    val fhirEngine = setUpEngine()

    val exception = assertFails {
      fhirEngine.search("CustomResource?active=true&gender=male&_sort=name")
    }

    assertTrue(exception.message!!.contains("CustomResource"))
  }

  @Test
  fun search_xFhirQuery_tagParam_returnsTaggedPatients() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(
      buildPatient("1", "Patient1", AdministrativeGender.Female)
        .copy(meta = Meta(tag = listOf(coding("https://d-tree.org/", "Tag1", "Tag 1")))),
      buildPatient("2", "Patient2", AdministrativeGender.Female)
        .copy(meta = Meta(tag = listOf(coding("http://d-tree.org/", "Tag2", "Tag 2")))),
    )

    val result = fhirEngine.search("Patient?_tag=Tag1").map { it.resource as Patient }

    assertEquals(1, result.size)
    assertTrue(result.all { patient -> patient.meta!!.tag.all { it.code?.value == "Tag1" } })
  }

  @Test
  fun search_xFhirQuery_profileParam_returnsProfiledPatients() = runTest {
    val fhirEngine = setUpEngine()
    val profile = "http://fhir.org/STU3/StructureDefinition/Example-Patient-Profile-1"
    fhirEngine.create(
      buildPatient("3", "C", AdministrativeGender.Female)
        .copy(meta = Meta(profile = listOf(Canonical(value = profile)))),
      buildPatient("4", "C", AdministrativeGender.Female)
        .copy(
          meta = Meta(profile = listOf(Canonical(value = "http://d-tree.org/Diabetes-Patient"))),
        ),
    )

    val result = fhirEngine.search("Patient?_profile=$profile").map { it.resource as Patient }

    assertEquals(1, result.size)
    assertTrue(result.all { patient -> patient.meta!!.profile.all { it.value == profile } })
  }

  @Test
  fun syncUpload_uploadLocalChange_success() = runTest {
    val fhirEngine = setUpEngine()
    val localChanges = mutableListOf<LocalChange>()
    val emittedProgress = mutableListOf<SyncUploadProgress>()

    fhirEngine
      .syncUpload(
        UploadStrategy.forBundleRequest(
          methodForCreate = HttpCreateMethod.PUT,
          methodForUpdate = HttpUpdateMethod.PATCH,
          squash = true,
          bundleSize = 500,
        ),
      ) { lcs, _ ->
        localChanges.addAll(lcs)
        flowOf(
          UploadRequestResult.Success(listOf(ResourceUploadResponseMapping(lcs, TEST_PATIENT_1))),
        )
      }
      .collect { emittedProgress.add(it) }

    assertEquals(1, localChanges.size)
    with(localChanges[0]) {
      assertEquals(ResourceType.Patient.name, resourceType)
      assertEquals(TEST_PATIENT_1.id, resourceId)
      assertEquals(LocalChange.Type.INSERT, type)
      assertEquals(fhirJsonParser.encodeToString(TEST_PATIENT_1), payload)
    }
    assertEquals(listOf(SyncUploadProgress(1, 1), SyncUploadProgress(0, 1)), emittedProgress)
  }

  @Test
  fun syncUpload_uploadLocalChange_failure() = runTest {
    val fhirEngine = setUpEngine()
    val emittedProgress = mutableListOf<SyncUploadProgress>()
    val uploadError = ResourceSyncException(ResourceType.Patient, "Did not work")

    fhirEngine
      .syncUpload(
        UploadStrategy.forBundleRequest(
          methodForCreate = HttpCreateMethod.PUT,
          methodForUpdate = HttpUpdateMethod.PATCH,
          squash = true,
          bundleSize = 500,
        ),
      ) { lcs, _ ->
        flowOf(UploadRequestResult.Failure(lcs, uploadError))
      }
      .collect { emittedProgress.add(it) }

    assertEquals(
      listOf(SyncUploadProgress(1, 1), SyncUploadProgress(1, 1, uploadError)),
      emittedProgress,
    )
  }

  @Test
  fun syncUpload_individualRequestStrategy_consumesLocalChanges() = runTest {
    val (fhirEngine, database) = setUpEngineWithDatabase()
    assertEquals(1, database.getLocalChangesCount())

    fhirEngine
      .syncUpload(
        UploadStrategy.forIndividualRequest(
          methodForCreate = HttpCreateMethod.PUT,
          methodForUpdate = HttpUpdateMethod.PATCH,
          squash = true,
        ),
      ) { lcs, _ ->
        flowOf(
          UploadRequestResult.Success(listOf(ResourceUploadResponseMapping(lcs, TEST_PATIENT_1))),
        )
      }
      .collect {}

    assertEquals(0, database.getLocalChangesCount())
  }

  @Test
  fun syncDownload_downloadResources() = runTest {
    val fhirEngine = setUpEngine()

    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(TEST_PATIENT_2)) }

    assertEquals(TEST_PATIENT_2_ID, fhirEngine.get<Patient>(TEST_PATIENT_2_ID).id)
  }

  @Test
  fun syncDownload_acceptLocalConflict_keepsLocalChangeAgainstRemoteVersion() = runTest {
    val (fhirEngine, database) = setUpEngineWithDatabase()
    val originalPatient =
      Patient(
        id = "original-002",
        meta = Meta(versionId = Id(value = "1"), lastUpdated = fhirInstant("2022-12-02T10:15:30Z")),
        name =
          listOf(
            HumanName(
              family = FhirString(value = "Stark"),
              given = listOf(FhirString(value = "Tony")),
            ),
          ),
      )
    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(originalPatient)) }
    var localChange =
      originalPatient.copy(address = listOf(Address(city = FhirString(value = "Malibu"))))
    fhirEngine.update(localChange)
    localChange =
      localChange.copy(
        address =
          localChange.address +
            Address(city = FhirString(value = "Malibu"), state = FhirString(value = "California")),
      )
    fhirEngine.update(localChange)
    val remoteChange =
      originalPatient.copy(
        meta = Meta(versionId = Id(value = "2"), lastUpdated = fhirInstant("2022-12-03T10:15:30Z")),
        address = listOf(Address(country = FhirString(value = "USA"))),
      )

    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(remoteChange)) }

    val localChangeDiff =
      """[{"op":"remove","path":"/address/0/country"},{"op":"add","path":"/address/0/city","value":"Malibu"},{"op":"add","path":"/address/-","value":{"city":"Malibu","state":"California"}}]"""
    assertEquals(
      localChangeDiff,
      database.getAllLocalChanges().first { it.resourceId == "original-002" }.payload,
    )
    assertEquals(
      fhirJsonParser.encodeToString(localChange),
      fhirJsonParser.encodeToString(fhirEngine.get<Patient>("original-002")),
    )
  }

  @Test
  fun syncDownload_updatesResourceEntityVersionIdAndLastUpdatedFromServer() = runTest {
    val (fhirEngine, database) = setUpEngineWithDatabase()
    val originalPatient =
      Patient(
        id = "original-002",
        meta = Meta(versionId = Id(value = "1"), lastUpdated = fhirInstant("2022-12-02T10:15:30Z")),
      )
    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(originalPatient)) }
    val updatedPatient =
      originalPatient.copy(
        meta = Meta(versionId = Id(value = "2"), lastUpdated = fhirInstant("2022-12-03T10:15:30Z")),
        address = listOf(Address(country = FhirString(value = "USA"))),
      )

    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(updatedPatient)) }

    val entity = database.selectEntity(ResourceType.Patient, "original-002")
    assertEquals("2", entity.versionId)
    assertEquals(Instant.parse("2022-12-03T10:15:30Z"), entity.lastUpdatedRemote)
  }

  @Test
  fun syncDownload_updatesLocalChangeVersionIdFromServer() = runTest {
    val fhirEngine = setUpEngine()
    val originalPatient =
      Patient(
        id = "original-002",
        meta = Meta(versionId = Id(value = "1"), lastUpdated = fhirInstant("2022-12-02T10:15:30Z")),
      )
    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(originalPatient)) }
    fhirEngine.update(
      originalPatient.copy(address = listOf(Address(city = FhirString(value = "Malibu")))),
    )
    val updatedPatient =
      originalPatient.copy(
        meta = Meta(versionId = Id(value = "2"), lastUpdated = fhirInstant("2022-12-03T10:15:30Z")),
        address = listOf(Address(country = FhirString(value = "USA"))),
      )

    fhirEngine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(updatedPatient)) }

    assertEquals(
      "2",
      fhirEngine.getLocalChanges(ResourceType.Patient, "original-002").first().versionId,
    )
  }

  @Test
  fun create_allowsSearchByLocalLastUpdated() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "patient-id-create"))
    val timestamp =
      fhirEngine.getLocalChanges(ResourceType.Patient, "patient-id-create")[0].timestamp

    val result =
      fhirEngine.search<Patient> {
        filter(
          DateClientParam(LOCAL_LAST_UPDATED),
          {
            value = of(FhirDateTime.fromString(timestamp.toString())!!)
            prefix = SearchComparator.Eq
          },
        )
      }

    // The seed patient can share the millisecond, so only membership is checked.
    assertTrue(result.any { it.resource.id == "patient-id-create" })
  }

  @Test
  fun update_allowsSearchByLocalLastUpdated() = runTest {
    val fhirEngine = setUpEngine()
    fhirEngine.create(Patient(id = "patient-id-update"))
    val createdAt =
      fhirEngine.getLocalChanges(ResourceType.Patient, "patient-id-update")[0].timestamp
    fhirEngine.update(
      Patient(
        id = "patient-id-update",
        name =
          listOf(
            HumanName(
              family = FhirString(value = "Doe"),
              given = listOf(FhirString(value = "John")),
            ),
          ),
      ),
    )
    val updatedAt =
      fhirEngine.getLocalChanges(ResourceType.Patient, "patient-id-update")[1].timestamp

    val result =
      fhirEngine.search<Patient> {
        filter(
          DateClientParam(LOCAL_LAST_UPDATED),
          {
            value = of(FhirDateTime.fromString(updatedAt.toString())!!)
            prefix = SearchComparator.Eq
          },
        )
      }

    assertTrue(updatedAt >= createdAt)
    // The seed patient can share the millisecond, so only membership is checked.
    assertTrue(result.any { it.resource.id == "patient-id-update" })
  }

  /** An engine over its own in-memory database, for tests that inspect the database directly. */
  private suspend fun setUpEngineWithDatabase(): Pair<FhirEngine, Database> {
    val database =
      DatabaseImpl(
        testPlatformContext(),
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        testStorageDirectory(),
        DatabaseConfig(inMemory = true),
      )
    databases.add(database)
    return FhirEngineImpl(database).apply { create(TEST_PATIENT_1) } to database
  }

  private fun buildPatient(id: String, given: String, gender: AdministrativeGender) =
    Patient(
      id = id,
      gender = Enumeration(value = gender),
      name = listOf(HumanName(given = listOf(FhirString(value = given)))),
    )

  private fun coding(system: String, code: String, display: String) =
    Coding(
      system = Uri(value = system),
      code = Code(value = code),
      display = FhirString(value = display),
    )

  private fun fhirInstant(iso: String) = FhirInstant(value = FhirDateTime.fromString(iso))

  companion object {
    private const val TEST_PATIENT_1_ID = "test_patient_1"
    private val TEST_PATIENT_1 = Patient(id = TEST_PATIENT_1_ID)

    private const val TEST_PATIENT_2_ID = "test_patient_2"
    private val TEST_PATIENT_2 = Patient(id = TEST_PATIENT_2_ID)
  }
}
