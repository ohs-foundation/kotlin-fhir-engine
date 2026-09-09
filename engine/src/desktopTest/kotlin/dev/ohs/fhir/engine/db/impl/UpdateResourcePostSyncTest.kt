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

import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The server assigns its own id to an uploaded resource, so the consolidator renames the local copy
 * and repoints everything that referred to it. Reached only after an upload, and covered until now
 * only through fakes.
 */
class UpdateResourcePostSyncTest {

  private val provider = SearchParamDefinitionsProviderImpl()

  private fun database() =
    DatabaseImpl(
      platformContext = Unit,
      resourceIndexer = ResourceIndexer(provider),
      storageDirectory = testStorageDirectory(),
    )

  @Test
  fun aServerAssignedIdRenamesTheResourceAndRepointsItsReferences() = runTest {
    val db = database()
    db.clearDatabase()
    db.insert(Patient(id = "p-local"))
    db.insert(
      Observation(
        id = "obs-1",
        status = Enumeration(value = Observation.ObservationStatus.Final),
        code = CodeableConcept(),
        subject = Reference(reference = FhirString(value = "Patient/p-local")),
      ),
    )

    db.updateResourcePostSync(
      oldResourceId = "p-local",
      newResourceId = "p-server",
      resourceType = ResourceType.Patient,
      versionId = "1",
      lastUpdated = Instant.parse("2026-08-12T00:00:00Z"),
    )

    assertEquals(
      "p-server",
      db.select(ResourceType.Patient, "p-server").id,
      "the local copy must carry the id the server assigned",
    )
    assertEquals(
      "Patient/p-server",
      (db.select(ResourceType.Observation, "obs-1") as Observation).subject?.reference?.value,
      "an observation left pointing at the pre-upload id is orphaned on the next sync",
    )
  }
}
