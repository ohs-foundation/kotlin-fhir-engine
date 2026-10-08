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
package dev.ohs.fhir.engine.db.impl

import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.LocalChangeToken
import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.db.impl.dao.LocalChangeDao
import dev.ohs.fhir.engine.db.impl.dao.diff
import dev.ohs.fhir.engine.impl.FhirEngineImpl
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.ResourceUploadResponseMapping
import dev.ohs.fhir.engine.sync.upload.UploadRequestResult
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Instant as FhirInstant
import dev.ohs.fhir.model.r4.Meta
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lives in desktopTest (constructs [DatabaseImpl] directly, which needs a platform DB). Only the
 * indexer-independent DB-layer tests are ported here:
 * - core CRUD + local-change + purge + counts + remote-insert meta
 * - the reference-cascade (`updateResourceAndReferences`) — the DB's unique non-trivial logic.
 *
 * Not ported (covered elsewhere / blocked / N/A):
 * - ~80 `search_*` execution tests — query generation is covered by SearchTest; execution for
 *   quantity/date/gender is blocked by the indexer (kotlin-fhir-path) limitations.
 * - migration and encryption tests, which live in `ResourceDatabaseMigrationTest` and
 *   `EncryptedDatabaseTest`.
 *
 * KMP adaptations: HAPI types → kotlin-fhir; assertResourceEquals → compare id/gender or serialized
 * form; `LocalChange.Type` enum at the Database layer; in-file DB cleared per test.
 */
class DatabaseImplTest {
  private lateinit var database: Database

  @BeforeTest
  fun setUp() = runTest {
    database =
      DatabaseImpl(
        Unit,
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        storageDirectory = testStorageDirectory(),
      )
    database.clearDatabase()
    database.insert(TEST_PATIENT_1)
  }

  @Test
  fun inMemory_createsNoDatabaseFile() = runTest {
    val directory = testStorageDirectory()!!
    val inMemoryDatabase =
      DatabaseImpl(
        platformContext = Unit,
        resourceIndexer = ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        storageDirectory = directory,
        config = DatabaseConfig(inMemory = true),
      )
    try {
      inMemoryDatabase.insert(Patient(id = "in-memory"))
      assertEquals("in-memory", inMemoryDatabase.select(ResourceType.Patient, "in-memory").id)
    } finally {
      inMemoryDatabase.close()
    }
    assertFalse(File(directory, "resources.db").exists())
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

  @Test
  fun update_existentResourceWithNoChange_shouldNotAddLocalChange() = runTest {
    database.insert(NAMED_PATIENT)
    val female = NAMED_PATIENT.copy(gender = Enumeration(value = AdministrativeGender.Female))
    database.update(female)
    val renamed = female.copy(name = listOf(humanName("Pieter", "TestPatient")))
    database.update(renamed)

    val changes = database.getAllLocalChanges().filter { it.resourceId == NAMED_PATIENT.id }
    assertEquals(
      listOf(LocalChange.Type.INSERT, LocalChange.Type.UPDATE, LocalChange.Type.UPDATE),
      changes.map { it.type },
    )

    database.update(renamed)

    assertEquals(3, database.getAllLocalChanges().count { it.resourceId == NAMED_PATIENT.id })
  }

  @Test
  fun getLocalChanges_withSingleLocalChange_shouldReturnIt() = runTest {
    database.insert(NAMED_PATIENT)

    val changes = database.getLocalChanges(ResourceType.Patient, NAMED_PATIENT.id!!)

    with(changes.single()) {
      assertEquals(NAMED_PATIENT.id, resourceId)
      assertEquals(ResourceType.Patient.name, resourceType)
      assertEquals(LocalChange.Type.INSERT, type)
      assertEquals(fhirJsonParser.encodeToString(NAMED_PATIENT), payload)
    }
  }

  @Test
  fun getLocalChanges_withMultipleLocalChanges_shouldReturnAllInOrder() = runTest {
    database.insert(NAMED_PATIENT)
    database.update(NAMED_PATIENT.copy(gender = Enumeration(value = AdministrativeGender.Female)))
    database.update(NAMED_PATIENT.copy(name = listOf(humanName("Pieter", "TestPatient"))))

    val changes = database.getLocalChanges(ResourceType.Patient, NAMED_PATIENT.id!!)

    assertTrue(changes.all { it.resourceId == NAMED_PATIENT.id })
    assertTrue(changes.all { it.resourceType == ResourceType.Patient.name })
    assertEquals(
      listOf(LocalChange.Type.INSERT, LocalChange.Type.UPDATE, LocalChange.Type.UPDATE),
      changes.map { it.type },
    )
  }

  @Test
  fun getLocalChanges_withWrongResourceId_shouldReturnEmpty() = runTest {
    database.insert(NAMED_PATIENT)

    assertTrue(database.getLocalChanges(ResourceType.Patient, "nonexistent_patient").isEmpty())
  }

  @Test
  fun getLocalChanges_withWrongResourceType_shouldReturnEmpty() = runTest {
    database.insert(NAMED_PATIENT)

    assertTrue(database.getLocalChanges(ResourceType.Encounter, NAMED_PATIENT.id!!).isEmpty())
  }

  @Test
  fun getAllChangesForEarliestChangedResource_shouldReturnTheFirstChangedResource() = runTest {
    database.insert(NAMED_PATIENT)
    database.insert(TEST_PATIENT_2)
    database.update(TEST_PATIENT_1.copy(gender = Enumeration(value = AdministrativeGender.Female)))

    val changes = database.getAllChangesForEarliestChangedResource()

    assertTrue(changes.isNotEmpty())
    assertTrue(changes.all { it.resourceId == TEST_PATIENT_1_ID })
  }

  @Test
  fun purge_withNoLocalChangeAndForcePurgeFalse_shouldPurgeResource() = runTest {
    database.insertRemote(TEST_PATIENT_2)
    assertTrue(database.getLocalChanges(ResourceType.Patient, TEST_PATIENT_2_ID).isEmpty())

    database.purge(ResourceType.Patient, setOf(TEST_PATIENT_2_ID))

    val exception =
      assertFailsWith<ResourceNotFoundException> {
        database.select(ResourceType.Patient, TEST_PATIENT_2_ID)
      }
    assertEquals(
      "Resource not found with type Patient and id $TEST_PATIENT_2_ID!",
      exception.message,
    )
  }

  @Test
  fun purge_withNoLocalChangeAndForcePurgeTrue_shouldPurgeResource() = runTest {
    database.insertRemote(TEST_PATIENT_2)

    database.purge(ResourceType.Patient, setOf(TEST_PATIENT_2_ID), forcePurge = true)

    assertFailsWith<ResourceNotFoundException> {
      database.select(ResourceType.Patient, TEST_PATIENT_2_ID)
    }
  }

  @Test
  fun update_remoteResourceWithLocalChange_shouldKeepVersionIdAndLastUpdated() = runTest {
    val patient =
      Patient(
        id = "remote-patient-1",
        name = listOf(humanName("FirstName", "FamilyName")),
        meta =
          Meta(
            versionId = Id(value = "remote-patient-1-version-001"),
            lastUpdated = REMOTE_LAST_UPDATED,
          ),
      )
    database.insertRemote(patient)

    database.update(
      Patient(
        id = "remote-patient-1",
        name = listOf(humanName("UpdatedFirstName", "UpdatedFamilyName")),
      ),
    )

    val entity = database.selectEntity(ResourceType.Patient, "remote-patient-1")
    assertEquals("remote-patient-1", entity.resourceId)
    assertEquals("remote-patient-1-version-001", entity.versionId)
    assertEquals(REMOTE_LAST_UPDATED_INSTANT, entity.lastUpdatedRemote)
    val change = database.getAllLocalChanges().first { it.resourceId == "remote-patient-1" }
    assertEquals("remote-patient-1-version-001", change.versionId)
  }

  @Test
  fun deleteUpdates_byToken_shouldDeleteLocalChanges() = runTest {
    database.insert(NAMED_PATIENT)
    database.update(NAMED_PATIENT.copy(name = listOf(humanName("Pieter", "TestPatient"))))
    val tokenIds =
      database
        .getAllLocalChanges()
        .filter { it.resourceId == NAMED_PATIENT.id }
        .flatMap { it.token.ids }

    database.deleteUpdates(LocalChangeToken(tokenIds))

    assertTrue(database.getAllLocalChanges().none { it.resourceId == NAMED_PATIENT.id })
  }

  @Test
  fun insertRemote_existingResource_shouldKeepTheEntityUuidAndId() = runTest {
    database.insertRemote(NAMED_PATIENT)
    val afterFirstSync = database.selectEntity(ResourceType.Patient, NAMED_PATIENT.id!!)

    database.insertRemote(NAMED_PATIENT)

    val afterSecondSync = database.selectEntity(ResourceType.Patient, NAMED_PATIENT.id!!)
    assertEquals(afterFirstSync.resourceUuid, afterSecondSync.resourceUuid)
    assertEquals(afterFirstSync.id, afterSecondSync.id)
  }

  @Test
  fun insertRemote_shouldSaveVersionIdAndLastUpdated() = runTest {
    database.insertRemote(
      Patient(
        id = "remote-patient-1",
        meta =
          Meta(
            versionId = Id(value = "remote-patient-1-version-1"),
            lastUpdated = REMOTE_LAST_UPDATED,
          ),
      ),
    )

    val entity = database.selectEntity(ResourceType.Patient, "remote-patient-1")
    assertEquals("remote-patient-1-version-1", entity.versionId)
    assertEquals(REMOTE_LAST_UPDATED_INSTANT, entity.lastUpdatedRemote)
  }

  @Test
  fun insertRemote_withNoMeta_shouldSaveNullVersionIdAndLastUpdated() = runTest {
    database.insertRemote(Patient(id = "remote-patient-2"))

    val entity = database.selectEntity(ResourceType.Patient, "remote-patient-2")
    assertNull(entity.versionId)
    assertNull(entity.lastUpdatedRemote)
  }

  @Test
  fun insert_withNoMeta_shouldSaveNullVersionIdAndLastUpdated() = runTest {
    database.insert(Patient(id = "local-patient-2"))

    val entity = database.selectEntity(ResourceType.Patient, "local-patient-2")
    assertNull(entity.versionId)
    assertNull(entity.lastUpdatedRemote)
  }

  @Test
  fun insert_thenUpload_shouldSaveTheServerVersionIdAndLastUpdated() = runTest {
    database.insert(Patient(id = "remote-patient-3"))
    database.deleteUpdates(listOf(TEST_PATIENT_1))
    val remoteMeta =
      Meta(
        versionId = Id(value = "remote-patient-3-version-001"),
        lastUpdated = REMOTE_LAST_UPDATED,
      )

    FhirEngineImpl(database)
      .syncUpload(
        UploadStrategy.forBundleRequest(
          methodForCreate = HttpCreateMethod.PUT,
          methodForUpdate = HttpUpdateMethod.PATCH,
          squash = true,
          bundleSize = 500,
        ),
      ) { localChanges, _ ->
        val change = localChanges.first { it.resourceId == "remote-patient-3" }
        flowOf(
          UploadRequestResult.Success(
            listOf(
              ResourceUploadResponseMapping(
                listOf(change),
                Patient(id = change.resourceId, meta = remoteMeta),
              ),
            ),
          ),
        )
      }
      .collect()

    val entity = database.selectEntity(ResourceType.Patient, "remote-patient-3")
    assertEquals("remote-patient-3-version-001", entity.versionId)
    assertEquals(REMOTE_LAST_UPDATED_INSTANT, entity.lastUpdatedRemote)
  }

  @Test
  fun insertRemote_manyResources_shouldNotInsertAnyLocalChange() = runTest {
    database.insertRemote(NAMED_PATIENT, TEST_PATIENT_2)

    assertTrue(
      database.getAllLocalChanges().none {
        it.resourceId in listOf(NAMED_PATIENT.id, TEST_PATIENT_2_ID)
      },
    )
  }

  @Test
  fun insert_existingResource_shouldReplaceOldIndexes() = runTest {
    database.insert(Patient(id = "local-1", name = listOf(humanName("Jane", "Doe"))))
    assertEquals(1, searchByGiven("Jane").size)

    database.insert(Patient(id = "local-1", name = listOf(humanName("John", "Doe"))))

    assertEquals(0, searchByGiven("Jane").size)
  }

  @Test
  fun insertRemote_existingResource_shouldReplaceOldIndexes() = runTest {
    database.insertRemote(Patient(id = "local-1", name = listOf(humanName("Jane", "Doe"))))
    assertEquals(1, searchByGiven("Jane").size)

    database.insertRemote(Patient(id = "local-1", name = listOf(humanName("John", "Doe"))))

    assertEquals(0, searchByGiven("Jane").size)
  }

  @Test
  fun update_shouldReplaceOldIndexes() = runTest {
    database.insertRemote(Patient(id = "local-1", name = listOf(humanName("Jane", "Doe"))))
    assertEquals(1, searchByGiven("Jane").size)

    database.update(Patient(id = "local-1", name = listOf(humanName("John", "Doe"))))

    assertEquals(0, searchByGiven("Jane").size)
  }

  @Test
  fun update_remoteResource_shouldRecordTheDiffAsALocalChange() = runTest {
    database.insertRemote(NAMED_PATIENT)
    val updated = NAMED_PATIENT.copy(name = listOf(humanName("Pieter", "TestPatient")))

    database.update(updated)

    val change = database.getAllLocalChanges().single { it.resourceId == NAMED_PATIENT.id }
    assertEquals(LocalChange.Type.UPDATE, change.type)
    assertEquals(ResourceType.Patient.name, change.resourceType)
    val expected =
      diff(fhirJsonParser.encodeToString(NAMED_PATIENT), fhirJsonParser.encodeToString(updated))
    assertEquals(
      Json.parseToJsonElement(expected).jsonArray.toSet(),
      Json.parseToJsonElement(change.payload).jsonArray.toSet(),
    )
  }

  @Test
  fun delete_remoteResource_shouldAddADeleteLocalChange() = runTest {
    database.insertRemote(TEST_PATIENT_2)

    database.delete(ResourceType.Patient, TEST_PATIENT_2_ID)

    val change = database.getAllLocalChanges().single { it.resourceId == TEST_PATIENT_2_ID }
    assertEquals(LocalChange.Type.DELETE, change.type)
    assertEquals(ResourceType.Patient.name, change.resourceType)
    assertNull(change.versionId)
    assertTrue(change.payload.isEmpty())
  }

  @Test
  fun getLocalChangesCount_noLocalChange_returnsZero() = runTest {
    database.deleteUpdates(listOf(TEST_PATIENT_1))

    assertEquals(0, database.getLocalChangesCount())
  }

  @Test
  fun getLocalChangesCount_twoLocalChanges_returnsTwo() = runTest {
    database.insert(TEST_PATIENT_2)

    assertEquals(2, database.getLocalChangesCount())
  }

  @Test
  fun updateResourceAndReferences_shouldKeepTheEntityUuid() = runTest {
    database.insert(LOCAL_PATIENT)
    val before = database.selectEntity(ResourceType.Patient, LOCAL_PATIENT.id!!)

    database.updateResourceAndReferences(
      LOCAL_PATIENT.id!!,
      LOCAL_PATIENT.copy(id = "remote-patient-1"),
    )

    val after = database.selectEntity(ResourceType.Patient, "remote-patient-1")
    assertEquals(before.resourceUuid, after.resourceUuid)
  }

  @Test
  fun updateResourceAndReferences_shouldUpdateTheLocalChangeResourceId() = runTest {
    database.insert(LOCAL_PATIENT)
    val entity = database.selectEntity(ResourceType.Patient, LOCAL_PATIENT.id!!)

    database.updateResourceAndReferences(
      LOCAL_PATIENT.id!!,
      LOCAL_PATIENT.copy(id = "remote-patient-1"),
    )

    val changes = database.getLocalChanges(entity.resourceUuid)
    assertTrue(changes.isNotEmpty())
    assertTrue(changes.all { it.resourceId == "remote-patient-1" })
  }

  @Test
  fun updateResourceAndReferences_shouldUpdateReferencesInInsertLocalChanges() = runTest {
    database.insert(LOCAL_PATIENT)
    database.insert(observationOf(LOCAL_PATIENT))

    database.updateResourceAndReferences(
      LOCAL_PATIENT.id!!,
      LOCAL_PATIENT.copy(id = "remote-patient-1"),
    )

    val change = database.getLocalChanges(ResourceType.Observation, "local-observation-1").single()
    assertEquals(LocalChange.Type.INSERT, change.type)
    val payload = fhirJsonParser.decodeFromString<Observation>(change.payload)
    assertEquals("Patient/remote-patient-1", payload.subject?.reference?.value)
  }

  @Test
  fun updateResourceAndReferences_shouldUpdateReferencesInUpdateLocalChanges() = runTest {
    database.insert(LOCAL_PATIENT)
    val observation = observationOf(LOCAL_PATIENT)
    database.insert(observation)
    database.update(
      observation.copy(
        performer =
          listOf(Reference(reference = FhirString(value = "Patient/${LOCAL_PATIENT.id}"))),
      ),
    )

    database.updateResourceAndReferences(
      LOCAL_PATIENT.id!!,
      LOCAL_PATIENT.copy(id = "remote-patient-1"),
    )

    val changes = database.getLocalChanges(ResourceType.Observation, "local-observation-1")
    assertEquals(2, changes.size)
    assertEquals(LocalChange.Type.UPDATE, changes[1].type)
    val operation = Json.parseToJsonElement(changes[1].payload).jsonArray.first().jsonObject
    assertEquals("Patient/remote-patient-1", operation["value"]!!.jsonPrimitive.content)
  }

  @Test
  fun updateResourceAndReferences_shouldMakeTheReferringResourceSearchableByTheNewId() = runTest {
    database.insert(LOCAL_PATIENT)
    database.insert(observationOf(LOCAL_PATIENT))

    database.updateResourceAndReferences(
      LOCAL_PATIENT.id!!,
      LOCAL_PATIENT.copy(id = "remote-patient-1"),
    )

    val observation =
      database.select(ResourceType.Observation, "local-observation-1") as Observation
    assertEquals("Patient/remote-patient-1", observation.subject?.reference?.value)
    val query =
      Search(ResourceType.Observation)
        .apply { filter(ReferenceClientParam("subject"), { value = "Patient/remote-patient-1" }) }
        .getQuery()
    assertEquals(
      listOf("local-observation-1"),
      database.search<Observation>(query).map { it.resource.id },
    )
  }

  @Test
  fun updateResourcePostSync_shouldUpdateResourceIdAndMeta() = runTest {
    database.insert(Patient(id = "patient1"))

    database.updateResourcePostSync(
      "patient1",
      "patient2",
      ResourceType.Patient,
      "1",
      REMOTE_LAST_UPDATED_INSTANT,
    )

    val entity = database.selectEntity(ResourceType.Patient, "patient2")
    assertEquals("patient2", entity.resourceId)
    assertEquals("1", entity.versionId)
    assertEquals(REMOTE_LAST_UPDATED_INSTANT, entity.lastUpdatedRemote)
  }

  @Test
  fun updateResourcePostSync_shouldDeleteTheOldResourceId() = runTest {
    database.insert(Patient(id = "patient1"))

    database.updateResourcePostSync("patient1", "patient2", ResourceType.Patient, null, null)

    val exception =
      assertFailsWith<ResourceNotFoundException> {
        database.select(ResourceType.Patient, "patient1")
      }
    assertEquals("Resource not found with type Patient and id patient1!", exception.message)
  }

  @Test
  fun updateResourcePostSync_shouldUpdateReferencesInReferringResourcesAndTheirLocalChanges() =
    runTest {
      val patient = Patient(id = "patient1")
      database.insert(patient, observationOf(patient))

      database.updateResourcePostSync(
        "patient1",
        "patient2",
        ResourceType.Patient,
        "1",
        REMOTE_LAST_UPDATED_INSTANT,
      )

      val observation =
        database.select(ResourceType.Observation, "local-observation-1") as Observation
      assertEquals("Patient/patient2", observation.subject?.reference?.value)
      val change =
        database.getLocalChanges(ResourceType.Observation, "local-observation-1").single()
      val payload = fhirJsonParser.decodeFromString<Observation>(change.payload)
      assertEquals("Patient/patient2", payload.subject?.reference?.value)
    }

  @Test
  fun getLocalChangeResourceReferences_aboveTheSQLiteVariableLimit_returnsEveryReference() =
    runTest {
      val patients =
        (1..LocalChangeDao.SQLITE_LIMIT_MAX_VARIABLE_NUMBER * 7).map {
          Patient(id = "local-patient-id$it", name = listOf(humanName("$it", "Family")))
        }
      database.insert(*patients.toTypedArray())
      // One reference per observation, the subject, so the count equals the patient count.
      database.insert(
        *patients
          .mapIndexed { index, patient ->
            observationOf(patient, "local-observation-$index").copy(performer = emptyList())
          }
          .toTypedArray(),
      )
      val localChangeIds = database.getAllLocalChanges().flatMap { it.token.ids }

      val references = database.getLocalChangeResourceReferences(localChangeIds)

      assertEquals(patients.size, references.size)
    }

  private suspend fun searchByGiven(given: String) =
    database.search<Patient>(
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("given"), { value = given }) }
        .getQuery(),
    )

  private fun observationOf(patient: Patient, id: String = "local-observation-1") =
    Observation(
      id = id,
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code = dev.ohs.fhir.model.r4.CodeableConcept(),
      subject = Reference(reference = FhirString(value = "Patient/${patient.id}")),
      performer = listOf(Reference(reference = FhirString(value = "Practitioner/123"))),
    )

  private fun humanName(given: String, family: String) =
    HumanName(family = FhirString(value = family), given = listOf(FhirString(value = given)))

  companion object {
    private const val TEST_PATIENT_1_ID = "test_patient_1"
    private val TEST_PATIENT_1 =
      Patient(id = TEST_PATIENT_1_ID, gender = Enumeration(value = AdministrativeGender.Male))

    private const val TEST_PATIENT_2_ID = "test_patient_2"
    private val TEST_PATIENT_2 =
      Patient(id = TEST_PATIENT_2_ID, gender = Enumeration(value = AdministrativeGender.Male))

    private val NAMED_PATIENT =
      Patient(
        id = "f001",
        gender = Enumeration(value = AdministrativeGender.Male),
        name =
          listOf(
            HumanName(
              family = FhirString(value = "van de Heuvel"),
              given = listOf(FhirString(value = "Pieter")),
            ),
          ),
      )
    private val LOCAL_PATIENT =
      Patient(
        id = "local-patient-1",
        name =
          listOf(
            HumanName(
              family = FhirString(value = "Family"),
              given = listOf(FhirString(value = "First Name")),
            ),
          ),
      )
    private const val REMOTE_LAST_UPDATED_STRING = "2024-04-08T11:15:42.648Z"
    private val REMOTE_LAST_UPDATED =
      FhirInstant(value = FhirDateTime.fromString(REMOTE_LAST_UPDATED_STRING))
    private val REMOTE_LAST_UPDATED_INSTANT = Instant.parse(REMOTE_LAST_UPDATED_STRING)
  }
}
