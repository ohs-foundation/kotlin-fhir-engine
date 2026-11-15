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
package dev.ohs.fhir.engine.db.impl.dao

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.engine.db.impl.ResourceDatabase
import dev.ohs.fhir.engine.db.impl.entities.LocalChangeEntity
import dev.ohs.fhir.engine.db.impl.entities.ResourceEntity
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.engine.resourceTypeEnum
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Lives in desktopTest (not commonTest) because it constructs a [ResourceDatabase] directly, which
 * needs the desktop in-memory Room builder + BundledSQLiteDriver.
 *
 * KMP adaptations, since the KMP engine serializes, diffs and extracts references differently from
 * HAPI:
 * - The INSERT payload is `fhirJsonParser` output.
 * - The UPDATE payload is an RFC 6902 patch from JsonDiff, not HAPI's zjsonpatch, with plain JSON
 *   pointer paths (no escaped slashes).
 * - Reference paths are JSON pointers (e.g. "/activity/0/detail/performer/0"), not FhirTerser paths
 *   ("activity.detail.performer").
 * - `LocalChangeEntity.type` is stored as an Int (`Type.*.value`).
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

  private companion object {
    const val PRACTITIONER = "Practitioner/Practitioner123"
  }

  private fun observation(id: String, subject: String, encounter: String? = null) =
    Observation(
      id = id,
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code = CodeableConcept(),
      subject = Reference(reference = FhirString(value = subject)),
      encounter = encounter?.let { Reference(reference = FhirString(value = it)) },
    )

  private fun carePlan(
    subject: String = "Patient/Patient1",
    performer: String,
    author: String? = null,
  ) =
    CarePlan(
      id = "CarePlan1",
      status = Enumeration(value = CarePlan.RequestStatus.Active),
      intent = Enumeration(value = CarePlan.CarePlanIntent.Plan),
      subject = Reference(reference = FhirString(value = subject)),
      author = author?.let { Reference(reference = FhirString(value = it)) },
      activity =
        listOf(
          CarePlan.Activity(
            detail =
              CarePlan.Activity.Detail(
                status = Enumeration(value = CarePlan.CarePlanActivityStatus.Not_Started),
                performer = listOf(Reference(reference = FhirString(value = performer))),
              ),
          ),
        ),
    )

  private fun resourceEntity(uuid: Uuid, resource: Resource) =
    ResourceEntity(
      id = 0,
      resourceUuid = uuid,
      resourceType = resource.resourceTypeEnum,
      resourceId = resource.id!!,
      serializedResource = fhirJsonParser.encodeToString(resource),
      versionId = null,
      lastUpdatedRemote = null,
      lastUpdatedLocal = Clock.System.now(),
    )

  private fun List<dev.ohs.fhir.engine.db.impl.entities.LocalChangeResourceReferenceEntity>
    .pathValuePairs() = map { it.resourceReferencePath to it.resourceReferenceValue }

  private val updatePatch =
    """[{"op":"add","path":"/author","value":{"reference":"$PRACTITIONER"}},""" +
      """{"op":"replace","path":"/activity/0/detail/performer/0/reference","value":"$PRACTITIONER"}]"""

  @Test
  fun addInsert_shouldAddLocalChangeAndLocalChangeReferences() = runTest {
    val carePlan = carePlan(performer = "Patient/Patient1")
    val uuid = Uuid.random()
    localChangeDao.addInsert(carePlan, uuid, Clock.System.now())

    val changes = localChangeDao.getLocalChanges(uuid)
    assertEquals(1, changes.size)
    val change = changes[0]
    assertEquals(uuid, change.resourceUuid)
    assertEquals("CarePlan1", change.resourceId)
    assertEquals(LocalChangeEntity.Type.INSERT.value, change.type)
    assertEquals(fhirJsonParser.encodeToString(carePlan), change.payload)

    assertEquals(
      listOf(
        "/subject" to "Patient/Patient1",
        "/activity/0/detail/performer/0" to "Patient/Patient1",
      ),
      localChangeDao.getReferencesForLocalChange(change.id).pathValuePairs(),
    )
  }

  @Test
  fun addUpdate_shouldAddLocalChangeAndLocalChangeReferences() = runTest {
    val original = carePlan(performer = "Patient/Patient1")
    val uuid = Uuid.random()
    localChangeDao.addInsert(original, uuid, Clock.System.now())

    val updated = carePlan(performer = PRACTITIONER, author = PRACTITIONER)
    localChangeDao.addUpdate(resourceEntity(uuid, original), updated, Clock.System.now())

    val changes = localChangeDao.getLocalChanges(uuid)
    assertEquals(2, changes.size)
    val insert = changes[0]
    assertEquals(uuid, insert.resourceUuid)
    assertEquals("CarePlan1", insert.resourceId)
    assertEquals(LocalChangeEntity.Type.INSERT.value, insert.type)
    assertEquals(fhirJsonParser.encodeToString(original), insert.payload)

    val update = changes[1]
    assertEquals(uuid, update.resourceUuid)
    assertEquals("CarePlan1", update.resourceId)
    assertEquals(LocalChangeEntity.Type.UPDATE.value, update.type)
    assertEquals(updatePatch, update.payload)

    // The removed nested performer, then the newly added author and nested performer.
    assertEquals(
      listOf(
        "/activity/0/detail/performer/0" to "Patient/Patient1",
        "/author" to PRACTITIONER,
        "/activity/0/detail/performer/0" to PRACTITIONER,
      ),
      localChangeDao.getReferencesForLocalChange(update.id).pathValuePairs(),
    )
  }

  @Test
  fun addDelete_shouldAddOnlyLocalChangeEntity() = runTest {
    val carePlan = carePlan(performer = "Patient/Patient1")
    val uuid = Uuid.random()
    localChangeDao.addInsert(carePlan, uuid, Clock.System.now())

    localChangeDao.addDelete(
      resourceId = "CarePlan1",
      resourceUuid = uuid,
      resourceType = ResourceType.CarePlan,
      remoteVersionId = null,
    )

    val changes = localChangeDao.getLocalChanges(uuid)
    assertEquals(2, changes.size)
    val insert = changes[0]
    assertEquals(uuid, insert.resourceUuid)
    assertEquals("CarePlan1", insert.resourceId)
    assertEquals(LocalChangeEntity.Type.INSERT.value, insert.type)
    assertEquals(fhirJsonParser.encodeToString(carePlan), insert.payload)

    val delete = changes[1]
    assertEquals(uuid, delete.resourceUuid)
    assertEquals("CarePlan1", delete.resourceId)
    assertEquals(LocalChangeEntity.Type.DELETE.value, delete.type)
    assertEquals("", delete.payload)
    assertEquals(0, localChangeDao.getReferencesForLocalChange(delete.id).size)
  }

  @Test
  fun updateResourceId_shouldUpdateLocalChangeAndLocalChangeReferences() = runTest {
    val patientUuid = Uuid.random()
    val patient = Patient(id = "Patient1")
    localChangeDao.addInsert(patient, patientUuid, Clock.System.now())

    val carePlanUuid = Uuid.random()
    val original = carePlan(performer = "Patient/Patient1")
    localChangeDao.addInsert(original, carePlanUuid, Clock.System.now())
    val updated = carePlan(performer = PRACTITIONER, author = PRACTITIONER)
    localChangeDao.addUpdate(resourceEntity(carePlanUuid, original), updated, Clock.System.now())

    val referringUuids =
      localChangeDao.updateResourceIdAndReferences(
        resourceUuid = patientUuid,
        oldResource = patient,
        updatedResourceId = "SyncedPatient1",
      )

    // The CarePlan referenced the Patient, so it is reported as referring.
    assertEquals(listOf(carePlanUuid), referringUuids)

    // The Patient's own local change carries the new id.
    val patientChanges = localChangeDao.getLocalChanges(patientUuid)
    assertEquals(1, patientChanges.size)
    assertEquals("SyncedPatient1", patientChanges[0].resourceId)

    // The CarePlan's INSERT and UPDATE come back in that order.
    val carePlanChanges = localChangeDao.getLocalChanges(carePlanUuid)
    assertEquals(2, carePlanChanges.size)

    val insert = carePlanChanges[0]
    assertEquals(carePlanUuid, insert.resourceUuid)
    assertEquals("CarePlan1", insert.resourceId)
    assertEquals(LocalChangeEntity.Type.INSERT.value, insert.type)
    assertEquals(
      fhirJsonParser.encodeToString(
        carePlan(subject = "Patient/SyncedPatient1", performer = "Patient/SyncedPatient1"),
      ),
      insert.payload,
    )
    assertEquals(
      listOf(
        "/subject" to "Patient/SyncedPatient1",
        "/activity/0/detail/performer/0" to "Patient/SyncedPatient1",
      ),
      localChangeDao.getReferencesForLocalChange(insert.id).pathValuePairs(),
    )

    val update = carePlanChanges[1]
    assertEquals(carePlanUuid, update.resourceUuid)
    assertEquals("CarePlan1", update.resourceId)
    assertEquals(LocalChangeEntity.Type.UPDATE.value, update.type)
    assertEquals(updatePatch, update.payload)
    assertEquals(
      listOf(
        "/activity/0/detail/performer/0" to "Patient/SyncedPatient1",
        "/author" to PRACTITIONER,
        "/activity/0/detail/performer/0" to PRACTITIONER,
      ),
      localChangeDao.getReferencesForLocalChange(update.id).pathValuePairs(),
    )
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
    assertEquals(
      listOf("Encounter/1", "Patient/1"),
      single.map { it.resourceReferenceValue }.sorted(),
    )

    val multiple =
      localChangeDao.getReferencesForLocalChanges(
        listOf(byResourceId["1"]!!.id, byResourceId["2"]!!.id),
      )
    assertEquals(
      listOf("Encounter/1", "Encounter/2", "Patient/1", "Patient/2"),
      multiple.map { it.resourceReferenceValue }.sorted(),
    )
  }
}
