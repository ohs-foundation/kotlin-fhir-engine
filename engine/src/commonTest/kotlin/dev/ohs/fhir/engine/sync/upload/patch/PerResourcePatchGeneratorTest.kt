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
package dev.ohs.fhir.engine.sync.upload.patch

import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.LocalChangeToken
import dev.ohs.fhir.engine.db.impl.dao.diff
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Meta
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

class PerResourcePatchGeneratorTest {
  private val patchGenerator = PerResourcePatchGenerator

  @Test
  fun generate_insert_returnsSingleInsertPatch() = runTest {
    val insertionLocalChange = createInsertLocalChange(PATIENT)

    val patches = patchGenerator.generate(listOf(insertionLocalChange), emptyList()).single()

    with(patches.patchMappings.single()) {
      with(generatedPatch) {
        assertEquals(Patch.Type.INSERT, type)
        assertEquals(PATIENT.id, resourceId)
        assertEquals(ResourceType.Patient.name, resourceType)
        assertEquals(fhirJsonParser.encodeToString(PATIENT), payload)
      }
      assertEquals(listOf(insertionLocalChange), localChanges)
    }
  }

  @Test
  fun generate_update_returnsSingleUpdatePatch() = runTest {
    val remotePatient = PATIENT.copy(meta = REMOTE_META)
    val updatedPatient1 = UPDATED_PATIENT_1.copy(meta = REMOTE_META)
    val updateLocalChange1 = createUpdateLocalChange(remotePatient, updatedPatient1, 1L)

    val patches = patchGenerator.generate(listOf(updateLocalChange1), emptyList()).single()

    with(patches.patchMappings.single()) {
      with(generatedPatch) {
        assertEquals(Patch.Type.UPDATE, type)
        assertEquals(remotePatient.id, resourceId)
        assertEquals(ResourceType.Patient.name, resourceType)
        assertEquals(REMOTE_VERSION_ID, versionId)
        assertEquals(operations(diff(remotePatient, updatedPatient1)), operations(payload))
      }
      assertEquals(listOf(updateLocalChange1), localChanges)
    }
  }

  @Test
  fun generate_delete_returnsSingleDeletePatch() = runTest {
    val remotePatient = PATIENT.copy(meta = REMOTE_META)
    val deleteLocalChange = createDeleteLocalChange(remotePatient, 3L)

    val patches = patchGenerator.generate(listOf(deleteLocalChange), emptyList()).single()

    with(patches.patchMappings.single()) {
      with(generatedPatch) {
        assertEquals(Patch.Type.DELETE, type)
        assertEquals(remotePatient.id, resourceId)
        assertEquals(ResourceType.Patient.name, resourceType)
        assertEquals(REMOTE_VERSION_ID, versionId)
        assertTrue(payload.isEmpty())
      }
      assertEquals(listOf(deleteLocalChange), localChanges)
    }
  }

  @Test
  fun generate_insertThenUpdate_returnsSingleInsertPatchWithTheUpdatedResource() = runTest {
    val insertionLocalChange = createInsertLocalChange(PATIENT)
    val updateLocalChange = createUpdateLocalChange(PATIENT, UPDATED_PATIENT_1, 1L)

    val patches =
      patchGenerator.generate(listOf(insertionLocalChange, updateLocalChange), emptyList()).single()

    with(patches.patchMappings.single()) {
      with(generatedPatch) {
        assertEquals(Patch.Type.INSERT, type)
        assertEquals(PATIENT.id, resourceId)
        assertEquals(ResourceType.Patient.name, resourceType)
        assertEquals(fhirJsonParser.encodeToString(UPDATED_PATIENT_1), payload)
      }
      assertEquals(listOf(insertionLocalChange, updateLocalChange), localChanges)
    }
  }

  @Test
  fun generate_insertThenDelete_returnsNoPatch() = runTest {
    val changes = listOf(createInsertLocalChange(PATIENT, 1L), createDeleteLocalChange(PATIENT, 2L))

    assertTrue(patchGenerator.generate(changes, emptyList()).isEmpty())
  }

  @Test
  fun generate_insertThenUpdateThenDelete_returnsNoPatch() = runTest {
    val changes =
      listOf(
        createInsertLocalChange(PATIENT, 1L),
        createUpdateLocalChange(PATIENT, UPDATED_PATIENT_1, 2L),
        createDeleteLocalChange(UPDATED_PATIENT_1, 3L),
      )

    assertTrue(patchGenerator.generate(changes, emptyList()).isEmpty())
  }

  @Test
  fun generate_updatedTwice_returnsSingleMergedUpdatePatch() = runTest {
    val remotePatient = PATIENT.copy(meta = REMOTE_META)
    val updatedPatient1 = UPDATED_PATIENT_1.copy(meta = REMOTE_META)
    val updatedPatient2 = UPDATED_PATIENT_2.copy(meta = REMOTE_META)
    val updateLocalChange1 = createUpdateLocalChange(remotePatient, updatedPatient1, 1L)
    val updateLocalChange2 = createUpdateLocalChange(updatedPatient1, updatedPatient2, 2L)

    val patches =
      patchGenerator.generate(listOf(updateLocalChange1, updateLocalChange2), emptyList()).single()

    with(patches.patchMappings.single()) {
      with(generatedPatch) {
        assertEquals(Patch.Type.UPDATE, type)
        assertEquals(remotePatient.id, resourceId)
        assertEquals(ResourceType.Patient.name, resourceType)
        assertEquals(REMOTE_VERSION_ID, versionId)
        assertEquals(operations(diff(remotePatient, updatedPatient2)), operations(payload))
      }
      assertEquals(listOf(updateLocalChange1, updateLocalChange2), localChanges)
    }
  }

  @Test
  fun generate_updatesAddingListItems_returnsSingleUpdatePatchWithEveryOperation() = runTest {
    val replaceStatus =
      """{"op":"replace","path":"/activity/0/detail/status","value":"in-progress"}"""
    val addViralLoad =
      """{"op":"add","path":"/activity/-","value":{"detail":{"status":"in-progress","description":"Viral Load Collection"}}}"""
    val addIndexCase =
      """{"op":"add","path":"/activity/-","value":{"detail":{"status":"in-progress","description":"Index Case Testing"}}}"""
    val carePlanId = "131b5257-a8b3-435a-8cb3-4cb1296be24a"
    val updateLocalChange1 =
      carePlanUpdate(carePlanId, "[$replaceStatus,$addViralLoad]", LocalChangeToken(listOf(1)))
    val updateLocalChange2 =
      carePlanUpdate(carePlanId, "[$addIndexCase]", LocalChangeToken(listOf(2)))

    val patches =
      patchGenerator.generate(listOf(updateLocalChange1, updateLocalChange2), emptyList()).single()

    with(patches.patchMappings.single().generatedPatch) {
      assertEquals(Patch.Type.UPDATE, type)
      assertEquals(carePlanId, resourceId)
      assertEquals("CarePlan", resourceType)
      assertEquals(operations("[$replaceStatus,$addViralLoad,$addIndexCase]"), operations(payload))
    }
  }

  @Test
  fun generate_updatedThenDeleted_returnsSingleDeletePatch() = runTest {
    val remotePatient = PATIENT.copy(meta = REMOTE_META)
    val updatedPatient1 = remotePatient.copy(name = listOf(humanName("John", "Doe")))
    val updatedPatient2 = updatedPatient1.copy(name = listOf(humanName("Jimmy", "Doe")))
    val updateLocalChange1 = createUpdateLocalChange(remotePatient, updatedPatient1, 1L)
    val updateLocalChange2 = createUpdateLocalChange(updatedPatient1, updatedPatient2, 2L)
    val deleteLocalChange = createDeleteLocalChange(updatedPatient2, 3L)

    val patches =
      patchGenerator
        .generate(listOf(updateLocalChange1, updateLocalChange2, deleteLocalChange), emptyList())
        .single()

    with(patches.patchMappings.single()) {
      with(generatedPatch) {
        assertEquals(Patch.Type.DELETE, type)
        assertEquals(remotePatient.id, resourceId)
        assertEquals(ResourceType.Patient.name, resourceType)
        assertEquals(REMOTE_VERSION_ID, versionId)
        assertTrue(payload.isEmpty())
      }
      assertEquals(listOf(updateLocalChange1, updateLocalChange2, deleteLocalChange), localChanges)
    }
  }

  @Test
  fun generate_changeAfterDelete_throws() = runTest {
    val changes =
      listOf(
        createDeleteLocalChange(PATIENT, 2L),
        createUpdateLocalChange(PATIENT, UPDATED_PATIENT_1, 3L),
      )

    val exception =
      assertFailsWith<IllegalArgumentException> { patchGenerator.generate(changes, emptyList()) }

    assertEquals("Changes after deletion of resource are not permitted", exception.message)
  }

  @Test
  fun generate_changeBeforeCreate_throws() = runTest {
    val changes =
      listOf(
        createUpdateLocalChange(PATIENT, UPDATED_PATIENT_1, 3L),
        createInsertLocalChange(PATIENT, 1L),
      )

    val exception =
      assertFailsWith<IllegalArgumentException> { patchGenerator.generate(changes, emptyList()) }

    assertEquals("Changes before creation of resource are not permitted", exception.message)
  }

  private fun operations(patch: String) = Json.parseToJsonElement(patch).jsonArray.toSet()

  private fun diff(source: Resource, target: Resource) =
    diff(fhirJsonParser.encodeToString(source), fhirJsonParser.encodeToString(target))

  private fun createInsertLocalChange(resource: Resource, currentChangeId: Long = 1L) =
    LocalChange(
      resourceId = resource.id!!,
      resourceType = ResourceType.Patient.name,
      type = LocalChange.Type.INSERT,
      payload = fhirJsonParser.encodeToString(resource),
      versionId = resource.meta?.versionId?.value,
      token = LocalChangeToken(listOf(currentChangeId)),
      timestamp = Clock.System.now(),
    )

  private fun createUpdateLocalChange(
    oldResource: Resource,
    updatedResource: Resource,
    currentChangeId: Long,
  ) =
    LocalChange(
      resourceId = oldResource.id!!,
      resourceType = ResourceType.Patient.name,
      type = LocalChange.Type.UPDATE,
      payload = diff(oldResource, updatedResource),
      versionId = oldResource.meta?.versionId?.value,
      token = LocalChangeToken(listOf(currentChangeId + 1)),
      timestamp = Clock.System.now(),
    )

  private fun createDeleteLocalChange(resource: Resource, currentChangeId: Long) =
    LocalChange(
      resourceId = resource.id!!,
      resourceType = ResourceType.Patient.name,
      type = LocalChange.Type.DELETE,
      payload = "",
      versionId = resource.meta?.versionId?.value,
      token = LocalChangeToken(listOf(currentChangeId + 1)),
      timestamp = Clock.System.now(),
    )

  private fun carePlanUpdate(id: String, payload: String, token: LocalChangeToken) =
    LocalChange(
      resourceType = "CarePlan",
      resourceId = id,
      type = LocalChange.Type.UPDATE,
      payload = payload,
      timestamp = Clock.System.now(),
      token = token,
    )

  private fun humanName(given: String, family: String) =
    HumanName(family = FhirString(value = family), given = listOf(FhirString(value = given)))

  private fun contactPoint(value: String) = ContactPoint(value = FhirString(value = value))

  private companion object {
    const val REMOTE_VERSION_ID = "patient-version-1"
    val REMOTE_META = Meta(versionId = Id(value = REMOTE_VERSION_ID))

    val PATIENT =
      Patient(
        id = "f001",
        name =
          listOf(
            HumanName(
              family = FhirString(value = "van de Heuvel"),
              given = listOf(FhirString(value = "Pieter")),
            ),
          ),
        telecom =
          listOf(
            ContactPoint(value = FhirString(value = "0648352638")),
            ContactPoint(value = FhirString(value = "p.heuvel@gmail.com")),
          ),
        address =
          listOf(
            Address(
              line = listOf(FhirString(value = "Van Egmondkade 23")),
              postalCode = FhirString(value = "1024 RJ"),
            ),
          ),
        contact =
          listOf(
            Patient.Contact(
              telecom = listOf(ContactPoint(value = FhirString(value = "0690383372"))),
            ),
          ),
      )

    val UPDATED_PATIENT_1 =
      PATIENT.copy(
        telecom =
          listOf(
            PATIENT.telecom[0],
            ContactPoint(value = FhirString(value = "p.heuvel@googlemail.com")),
          ),
        address =
          listOf(
            Address(
              line = listOf(FhirString(value = "Herengracht 609")),
              postalCode = FhirString(value = "1017 CE"),
            ),
          ),
        contact =
          listOf(
            Patient.Contact(
              telecom = listOf(ContactPoint(value = FhirString(value = "0123456789"))),
            ),
          ),
      )

    val UPDATED_PATIENT_2 =
      UPDATED_PATIENT_1.copy(
        telecom =
          listOf(
            ContactPoint(value = FhirString(value = "555-1337")),
            UPDATED_PATIENT_1.telecom[1],
          ),
        contact =
          listOf(
            Patient.Contact(
              telecom = listOf(ContactPoint(value = FhirString(value = "001122334455"))),
            ),
          ),
      )
  }
}
