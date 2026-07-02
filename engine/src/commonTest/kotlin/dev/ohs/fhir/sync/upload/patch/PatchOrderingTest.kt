/*
 * Copyright 2024-2026 Open Health Stack Foundation
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
package dev.ohs.fhir.sync.upload.patch

import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.LocalChangeToken
import dev.ohs.fhir.db.LocalChangeResourceReference
import dev.ohs.fhir.db.impl.JsonDiff
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.resourceType
import dev.ohs.fhir.sync.upload.patch.PatchOrdering.createAdjacencyListForCreateReferences
import dev.ohs.fhir.versionId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import kotlin.test.Test
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class PatchOrderingTest {

  private val patchGenerator = PerResourcePatchGenerator

  @Test
  fun create_reference_adjacency_list_with_local_changes_for_only_new_resources_should_only_have_edges_to_inserted_resources() =
    runTest {
      val helper = LocalChangeHelper()

      var group = helper.createGroup("group-1", 1)
      helper.createPatient("patient-1", 2)
      helper.createEncounter("encounter-1", 3, "Patient/patient-1")
      helper.createObservation("observation-1", 4, "Patient/patient-1", "Encounter/encounter-1")
      group = helper.updateGroup(group, 5, "Patient/patient-1")

      helper.createPatient("patient-2", 6)
      helper.createEncounter("encounter-2", 7, "Patient/patient-2")
      helper.createObservation("observation-2", 8, "Patient/patient-2", "Encounter/encounter-2")
      group = helper.updateGroup(group, 9, "Patient/patient-2")

      helper.createPatient("patient-3", 10)
      helper.createEncounter("encounter-3", 11, "Patient/patient-3")
      helper.createObservation("observation-3", 12, "Patient/patient-3", "Encounter/encounter-3")
      group = helper.updateGroup(group, 13, "Patient/patient-3")

      val result =
        patchGenerator
          .generateSquashedChangesMapping(helper.localChanges)
          .createAdjacencyListForCreateReferences(
            helper.localChangeResourceReferences.groupBy { it.localChangeId },
          )

      result.shouldBeEqual(
        mapOf(
          "Group/group-1" to listOf("Patient/patient-1", "Patient/patient-2", "Patient/patient-3"),
          "Patient/patient-1" to emptyList(),
          "Patient/patient-2" to emptyList(),
          "Patient/patient-3" to emptyList(),
          "Encounter/encounter-1" to listOf("Patient/patient-1"),
          "Encounter/encounter-2" to listOf("Patient/patient-2"),
          "Encounter/encounter-3" to listOf("Patient/patient-3"),
          "Observation/observation-1" to listOf("Patient/patient-1", "Encounter/encounter-1"),
          "Observation/observation-2" to listOf("Patient/patient-2", "Encounter/encounter-2"),
          "Observation/observation-3" to listOf("Patient/patient-3", "Encounter/encounter-3"),
        ),
      )
    }

  @Test
  fun create_reference_adjacency_list_with_local_changes_for_new_and_old_resources_should_only_have_edges_to_inserted_resources() =
    runTest {
      val helper = LocalChangeHelper()
      var group = helper.createGroup("group-1", 1)
      // The scenario is that Patient is already created on the server and device is just updating
      // it.
      helper.updatePatient(Patient(id = "patient-1"), 2)
      helper.createEncounter("encounter-1", 3, "Patient/patient-1")
      helper.createObservation("observation-1", 4, "Patient/patient-1", "Encounter/encounter-1")

      group = helper.updateGroup(group, 5, "Patient/patient-1")
      // The scenario is that Patient is already created on the server and device is just updating
      // it.
      helper.updatePatient(Patient(id = "patient-2"), 6)
      helper.createEncounter("encounter-2", 7, "Patient/patient-2")
      helper.createObservation("observation-2", 8, "Patient/patient-2", "Encounter/encounter-2")

      group = helper.updateGroup(group, 9, "Patient/patient-2")
      // The scenario is that Patient is already created on the server and device is just updating
      // it.
      helper.updatePatient(Patient(id = "patient-3"), 10)
      helper.createEncounter("encounter-3", 11, "Patient/patient-3")
      helper.createObservation("observation-3", 12, "Patient/patient-3", "Encounter/encounter-3")
      group = helper.updateGroup(group, 13, "Patient/patient-3")

      val result =
        patchGenerator
          .generateSquashedChangesMapping(helper.localChanges)
          .createAdjacencyListForCreateReferences(
            helper.localChangeResourceReferences.groupBy { it.localChangeId },
          )

      result.shouldBeEqual(
        mapOf(
          "Group/group-1" to emptyList(),
          "Patient/patient-1" to emptyList(),
          "Patient/patient-2" to emptyList(),
          "Patient/patient-3" to emptyList(),
          "Encounter/encounter-1" to emptyList(),
          "Encounter/encounter-2" to emptyList(),
          "Encounter/encounter-3" to emptyList(),
          "Observation/observation-1" to listOf("Encounter/encounter-1"),
          "Observation/observation-2" to listOf("Encounter/encounter-2"),
          "Observation/observation-3" to listOf("Encounter/encounter-3"),
        ),
      )
    }

  @Test
  fun generate_with_acyclic_references_should_return_the_list_in_topological_order() = runTest {
    val helper = LocalChangeHelper()
    var group = helper.createGroup("group-1", 1)

    group = helper.updateGroup(group, 2, "Patient/patient-1")
    helper.createObservation("observation-1", 3, "Patient/patient-1", "Encounter/encounter-1")
    helper.createEncounter("encounter-1", 4, "Patient/patient-1")
    helper.createPatient("patient-1", 5)

    group = helper.updateGroup(group, 6, "Patient/patient-2")
    helper.createObservation("observation-2", 7, "Patient/patient-2", "Encounter/encounter-2")
    helper.createEncounter("encounter-2", 8, "Patient/patient-2")
    helper.createPatient("patient-2", 9)

    group = helper.updateGroup(group, 10, "Patient/patient-3")
    helper.createObservation("observation-3", 11, "Patient/patient-3", "Encounter/encounter-3")
    helper.createEncounter("encounter-3", 12, "Patient/patient-3")
    helper.createPatient("patient-3", 13)

    val result = patchGenerator.generate(helper.localChanges, helper.localChangeResourceReferences)

    // This order is based on the current implementation of the topological sort in [PatchOrdering],
    // it's entirely possible to generate different order here which is acceptable/correct, should
    // we have a different implementation of the topological sort.
    result
      .map { it.patchMappings.single().generatedPatch.resourceId }
      .shouldContainExactly(
        "patient-1",
        "patient-2",
        "patient-3",
        "group-1",
        "encounter-1",
        "observation-1",
        "encounter-2",
        "observation-2",
        "encounter-3",
        "observation-3",
      )
  }

  @Test
  fun generate_with_cyclic_and_acyclic_references_should_generate_both_individual_and_combined_mappings() =
    runTest {
      val helper = LocalChangeHelper()

      // Patient and RelatedPerson have cyclic dependency
      helper.createPatient("patient-1", 1, "related-1")
      helper.createRelatedPerson("related-1", 2, "Patient/patient-1")

      // Patient, RelatedPerson have cyclic dependency. Observation, Encounter and Patient have
      // acyclic dependency and order doesn't matter since they all go in same bundle.
      helper.createPatient("patient-2", 3, "related-2")
      helper.createRelatedPerson("related-2", 4, "Patient/patient-2")
      helper.createObservation("observation-1", 5, "Patient/patient-2", "Encounter/encounter-1")
      helper.createEncounter("encounter-1", 6, "Patient/patient-2")

      // observation , encounter and Patient have acyclic dependency with each other, hence order is
      // important here.
      helper.createObservation("observation-2", 7, "Patient/patient-3", "Encounter/encounter-2")
      helper.createEncounter("encounter-2", 8, "Patient/patient-3")
      helper.createPatient("patient-3", 9)

      val result =
        patchGenerator.generate(helper.localChanges, helper.localChangeResourceReferences)

      result
        .map { it.patchMappings.map { it.generatedPatch.resourceId } }
        .shouldContainExactly(
          listOf("patient-1", "related-1"),
          listOf("patient-2", "related-2"),
          listOf("encounter-1"),
          listOf("observation-1"),
          listOf("patient-3"),
          listOf("encounter-2"),
          listOf("observation-2"),
        )
    }

  companion object {
    private val jsonParser = FhirR4Json()

    private fun createUpdateLocalChange(
      oldEntity: Resource,
      updatedResource: Resource,
      currentChangeId: Long,
    ): LocalChange {
      val jsonDiff =
        JsonDiff.diff(
          jsonParser.encodeToString(oldEntity),
          jsonParser.encodeToString(updatedResource),
        )
      return LocalChange(
        resourceId = oldEntity.id!!,
        resourceType = oldEntity.resourceType,
        type = LocalChange.Type.UPDATE,
        payload = jsonDiff,
        versionId = oldEntity.versionId,
        token = LocalChangeToken(listOf(currentChangeId)),
        timestamp = Clock.System.now(),
      )
    }

    private fun createInsertLocalChange(entity: Resource, currentChangeId: Long = 1): LocalChange {
      return LocalChange(
        resourceId = entity.id!!,
        resourceType = entity.resourceType,
        type = LocalChange.Type.INSERT,
        payload = jsonParser.encodeToString(entity),
        versionId = entity.versionId,
        token = LocalChangeToken(listOf(currentChangeId)),
        timestamp = Clock.System.now(),
      )
    }
  }

  internal class LocalChangeHelper {
    val localChanges = ArrayDeque<LocalChange>()
    val localChangeResourceReferences = mutableListOf<LocalChangeResourceReference>()

    fun createGroup(
      id: String,
      changeId: Long,
    ) =
      Group(
          id = id,
          type = Enumeration(value = Group.GroupType.Person),
          actual = dev.ohs.fhir.model.r4.Boolean(value = true),
        )
        .also { localChanges.add(createInsertLocalChange(it, changeId)) }

    fun updateGroup(
      group: Group,
      changeId: Long,
      member: String,
    ) =
      group
        .copy(
          member =
            group.member +
              Group.Member(
                entity = Reference(reference = dev.ohs.fhir.model.r4.String(value = member)),
              ),
        )
        .also {
          localChanges.add(createUpdateLocalChange(group, it, changeId))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              changeId,
              member,
              "Group.member",
            ),
          )
        }

    fun createPatient(
      id: String,
      changeId: Long,
      relatedPersonId: String? = null,
    ) =
      Patient(id = id)
        .toBuilder()
        .apply {
          relatedPersonId?.let {
            link.add(
              Patient.Link(
                  other =
                    Reference(
                      reference = dev.ohs.fhir.model.r4.String(value = "RelatedPerson/$it"),
                    ),
                  type = Enumeration(value = Patient.LinkType.Seealso),
                )
                .toBuilder(),
            )
          }
        }
        .build()
        .also {
          localChanges.add(createInsertLocalChange(it, changeId))
          relatedPersonId?.let {
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "RelatedPerson/$relatedPersonId",
                "Patient.other",
              ),
            )
          }
        }

    fun updatePatient(
      patient: Patient,
      changeId: Long,
    ) =
      patient.copy(active = dev.ohs.fhir.model.r4.Boolean(value = true)).also {
        localChanges.add(createUpdateLocalChange(patient, it, changeId))
      }

    fun createEncounter(
      id: String,
      changeId: Long,
      subject: String,
    ) =
      Encounter(
          id = id,
          subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = subject)),
          status = Enumeration(value = Encounter.EncounterStatus.Planned),
          `class` = Coding(code = Code(value = "AMB")),
        )
        .also {
          localChanges.add(createInsertLocalChange(it, changeId))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              changeId,
              subject,
              "Encounter.subject",
            ),
          )
        }

    fun createObservation(
      id: String,
      changeId: Long,
      subject: String,
      encounter: String,
    ) =
      Observation(
          id = id,
          subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = subject)),
          encounter = Reference(reference = dev.ohs.fhir.model.r4.String(value = encounter)),
          status = Enumeration(value = Observation.ObservationStatus.Registered),
          code = CodeableConcept(),
        )
        .also {
          localChanges.add(createInsertLocalChange(it, changeId))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              changeId,
              subject,
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              changeId,
              encounter,
              "Observation.encounter",
            ),
          )
        }

    fun createRelatedPerson(
      id: String,
      changeId: Long,
      patient: String,
    ) =
      RelatedPerson(
          id = id,
          patient = Reference(reference = dev.ohs.fhir.model.r4.String(value = patient)),
        )
        .also {
          localChanges.add(createInsertLocalChange(it, changeId))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              patient,
              "RelatedPerson.patient",
            ),
          )
        }
  }
}
