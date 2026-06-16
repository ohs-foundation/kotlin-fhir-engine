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

package dev.ohs.fhir.db.impl.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.db.impl.ResourceDatabase
import dev.ohs.fhir.db.impl.entities.LocalChangeEntity
import dev.ohs.fhir.db.impl.entities.ResourceEntity
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Adapted from engine/src/androidTest/.../db/impl/dao/LocalChangeDaoTest.kt
 *
 * Lives in desktopTest (not commonTest) because it constructs a [ResourceDatabase] directly, which
 * needs the desktop in-memory Room builder + BundledSQLiteDriver.
 *
 * KMP-adapted assertions (engine-kmp's serialization/diff/reference-extraction differ from HAPI):
 * - payload (INSERT) is `FhirR4Json` output; payload (UPDATE) is an RFC 6902 patch with
 *   JSON-pointer paths (no escaped slashes), produced by JsonDiff — not HAPI's zjsonpatch.
 * - reference paths are JSON-pointer (e.g. "/subject"), not FhirTerser paths ("subject");
 *   references are extracted into a Set, so assertions are order-independent.
 * - `LocalChangeEntity.type` is stored as an Int (`Type.*.value`).
 * - Uses [Observation] (constructible, has reference fields) where the engine used CarePlan/Task.
 */
class LocalChangeDaoTest {
  private lateinit var database: ResourceDatabase
  private lateinit var localChangeDao: LocalChangeDao

  @BeforeTest
  fun setUp() {
    database =
      Room.inMemoryDatabaseBuilder<ResourceDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    localChangeDao = database.localChangeDao()
  }

  @AfterTest
  fun tearDown() {
    database.close()
  }

  private fun observation(id: String, subject: String, encounter: String? = null) =
    Observation(
      id = id,
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code = CodeableConcept(),
      subject = Reference(reference = FhirString(value = subject)),
      encounter = encounter?.let { Reference(reference = FhirString(value = it)) },
    )

  private fun resourceEntity(uuid: Uuid, resource: Observation) =
    ResourceEntity(
      id = 0,
      resourceUuid = uuid,
      resourceType = ResourceType.Observation,
      resourceId = resource.id!!,
      serializedResource = FhirR4Json().encodeToString(resource),
      versionId = null,
      lastUpdatedRemote = null,
      lastUpdatedLocal = Clock.System.now(),
    )

  private fun List<dev.ohs.fhir.db.impl.entities.LocalChangeResourceReferenceEntity>
    .pathValuePairs() = map { it.resourceReferencePath to it.resourceReferenceValue }.toSet()

  @Test
  fun addInsert_shouldAddLocalChangeAndLocalChangeReferences() = runTest {
    val obs = observation("o1", "Patient/Patient1", "Encounter/Enc1")
    val uuid = Uuid.random()
    localChangeDao.addInsert(obs, uuid, Clock.System.now())

    val changes = localChangeDao.getLocalChanges(uuid)
    assertEquals(1, changes.size)
    val change = changes[0]
    assertEquals(uuid, change.resourceUuid)
    assertEquals("o1", change.resourceId)
    assertEquals(LocalChangeEntity.Type.INSERT.value, change.type)
    assertEquals(FhirR4Json().encodeToString(obs), change.payload)

    val references = localChangeDao.getReferencesForLocalChange(change.id)
    assertEquals(2, references.size)
    assertEquals(
      setOf("/subject" to "Patient/Patient1", "/encounter" to "Encounter/Enc1"),
      references.pathValuePairs(),
    )
  }

  @Test
  fun addUpdate_shouldAddLocalChangeAndLocalChangeReferences() = runTest {
    val original = observation("o1", "Patient/1", "Encounter/1")
    val uuid = Uuid.random()
    localChangeDao.addInsert(original, uuid, Clock.System.now())

    val updated = observation("o1", "Patient/2", "Encounter/1")
    localChangeDao.addUpdate(resourceEntity(uuid, original), updated, Clock.System.now())

    val changes = localChangeDao.getLocalChanges(uuid)
    assertEquals(2, changes.size)
    assertEquals(LocalChangeEntity.Type.INSERT.value, changes[0].type)

    val update = changes[1]
    assertEquals(uuid, update.resourceUuid)
    assertEquals("o1", update.resourceId)
    assertEquals(LocalChangeEntity.Type.UPDATE.value, update.type)
    assertEquals(
      """[{"op":"replace","path":"/subject/reference","value":"Patient/2"}]""",
      update.payload,
    )

    // The reference diff between the two versions: the old subject is removed, the new one added.
    val references = localChangeDao.getReferencesForLocalChange(update.id)
    assertEquals(
      setOf("/subject" to "Patient/1", "/subject" to "Patient/2"),
      references.pathValuePairs(),
    )
  }

  @Test
  fun addDelete_shouldAddOnlyLocalChangeEntity() = runTest {
    val obs = observation("o1", "Patient/1")
    val uuid = Uuid.random()
    localChangeDao.addInsert(obs, uuid, Clock.System.now())

    localChangeDao.addDelete(
      resourceId = "o1",
      resourceUuid = uuid,
      resourceType = ResourceType.Observation,
      remoteVersionId = null,
    )

    val changes = localChangeDao.getLocalChanges(uuid)
    assertEquals(2, changes.size)
    assertEquals(LocalChangeEntity.Type.INSERT.value, changes[0].type)

    val delete = changes[1]
    assertEquals(LocalChangeEntity.Type.DELETE.value, delete.type)
    assertEquals("", delete.payload)
    assertEquals(0, localChangeDao.getReferencesForLocalChange(delete.id).size)
  }

  @Test
  fun updateResourceId_shouldUpdateLocalChangeAndLocalChangeReferences() = runTest {
    val patientUuid = Uuid.random()
    val patient = Patient(id = "Patient1")
    localChangeDao.addInsert(patient, patientUuid, Clock.System.now())

    val obsUuid = Uuid.random()
    val obs = observation("o1", "Patient/Patient1")
    localChangeDao.addInsert(obs, obsUuid, Clock.System.now())

    val referringUuids =
      localChangeDao.updateResourceIdAndReferences(
        resourceUuid = patientUuid,
        oldResource = patient,
        updatedResourceId = "SyncedPatient1",
      )

    // The Observation referenced the Patient, so it is reported as referring.
    assertEquals(listOf(obsUuid), referringUuids)

    // The Patient's own local change now carries the new id.
    val patientChanges = localChangeDao.getLocalChanges(patientUuid)
    assertEquals(1, patientChanges.size)
    assertEquals("SyncedPatient1", patientChanges[0].resourceId)

    // The Observation's INSERT payload + references now point at the new id.
    val obsChanges = localChangeDao.getLocalChanges(obsUuid)
    assertEquals(1, obsChanges.size)
    assertEquals(LocalChangeEntity.Type.INSERT.value, obsChanges[0].type)
    val references = localChangeDao.getReferencesForLocalChange(obsChanges[0].id)
    assertEquals(setOf("/subject" to "Patient/SyncedPatient1"), references.pathValuePairs())
  }

  @Test
  fun updateResourceIdAndReferences_shouldSafelyUpdateReferencesAboveSQLiteInOpLimit() = runTest {
    val patientUuid = Uuid.random()
    val patient = Patient(id = "local-patient-id")
    localChangeDao.addInsert(patient, patientUuid, Clock.System.now())

    // Exceed the SQLite IN(..) variable limit so the chunked fetch path is exercised.
    val countAboveLimit = LocalChangeDao.SQLITE_LIMIT_MAX_VARIABLE_NUMBER + 50
    repeat(countAboveLimit) {
      localChangeDao.addInsert(
        observation("obs-$it", "Patient/local-patient-id"),
        Uuid.random(),
        Clock.System.now(),
      )
    }

    val updatedReferences =
      localChangeDao.updateResourceIdAndReferences(
        resourceUuid = patientUuid,
        oldResource = patient,
        updatedResourceId = "synced-patient-id",
      )
    assertEquals(countAboveLimit, updatedReferences.size)
  }

  @Test
  fun getReferencesForLocalChanges_shouldReturnAllChanges() = runTest {
    listOf(
        observation("1", "Patient/1", "Encounter/1"),
        observation("2", "Patient/2", "Encounter/2"),
      )
      .forEach { localChangeDao.addInsert(it, Uuid.random(), Clock.System.now()) }

    val byResourceId = localChangeDao.getAllLocalChanges().associateBy { it.resourceId }

    val single = localChangeDao.getReferencesForLocalChanges(listOf(byResourceId["1"]!!.id))
    assertEquals(2, single.size)
    assertEquals(
      setOf("Patient/1", "Encounter/1"),
      single.map { it.resourceReferenceValue }.toSet(),
    )

    val multiple =
      localChangeDao.getReferencesForLocalChanges(
        listOf(byResourceId["1"]!!.id, byResourceId["2"]!!.id),
      )
    assertEquals(4, multiple.size)
    assertTrue(
      multiple.map { it.resourceReferenceValue }.toSet() ==
        setOf("Patient/1", "Encounter/1", "Patient/2", "Encounter/2"),
    )
  }
}
