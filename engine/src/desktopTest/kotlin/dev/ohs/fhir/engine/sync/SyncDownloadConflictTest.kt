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
package dev.ohs.fhir.engine.sync

import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.resourceType
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Which downloaded resources `syncDownload` treats as conflicting.
 *
 * A conflict is a downloaded resource that the client has already edited. Detection asks the
 * database which of the page has a pending change, so it has to match on the resource's type as
 * well as its id: a pending change to one type says nothing about another that happens to share an
 * id, and resolving it would look for a local resource that does not exist.
 */
class SyncDownloadConflictTest {

  private val resolvedKeys = mutableListOf<String>()

  private val recordingResolver = ConflictResolver { local, remote ->
    resolvedKeys += "${local.resourceType}/${local.id.orEmpty()}"
    Resolved(remote)
  }

  @BeforeTest
  fun setUp() {
    resolvedKeys.clear()
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.clearInstance()
  }

  @Test
  fun `a downloaded resource edited locally is resolved`() = runTest {
    val engine = FhirEngineProvider.getInstance()
    engine.create(patient(EDITED_ID, family = "Local"))

    engine.syncDownload(recordingResolver) { flowOf(listOf(patient(EDITED_ID, family = "Remote"))) }

    assertEquals(listOf("Patient/$EDITED_ID"), resolvedKeys)
  }

  @Test
  fun `a downloaded resource with no local edit is not resolved`() = runTest {
    val engine = FhirEngineProvider.getInstance()

    engine.syncDownload(recordingResolver) { flowOf(listOf(patient(FRESH_ID, family = "Remote"))) }

    assertEquals(emptyList(), resolvedKeys)
    assertEquals(FRESH_ID, engine.get(ResourceType.Patient, FRESH_ID).id)
  }

  @Test
  fun `a pending change to another type sharing the id is not a conflict`() = runTest {
    val engine = FhirEngineProvider.getInstance()
    // Edited locally, and the download carries a Patient with the same logical id. Matching on the
    // id alone would call the resolver for it, then look up a Patient that was never stored.
    engine.create(observation(SHARED_ID))

    engine.syncDownload(recordingResolver) { flowOf(listOf(patient(SHARED_ID, family = "Remote"))) }

    assertEquals(emptyList(), resolvedKeys)
    assertEquals(SHARED_ID, engine.get(ResourceType.Patient, SHARED_ID).id)
  }

  @Test
  fun `a page resolves only the resources that were edited`() = runTest {
    val engine = FhirEngineProvider.getInstance()
    engine.create(patient(EDITED_ID, family = "Local"))

    val page: List<Resource> =
      listOf(patient(EDITED_ID, family = "Remote"), patient(FRESH_ID, family = "Remote"))
    engine.syncDownload(recordingResolver) { flowOf(page) }

    assertEquals(listOf("Patient/$EDITED_ID"), resolvedKeys)
  }

  private fun patient(id: String, family: String) =
    Patient(
      id = id,
      active = FhirBoolean(value = true),
      name = listOf(HumanName(family = FhirString(value = family))),
    )

  private fun observation(id: String) =
    Observation(
      id = id,
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code = CodeableConcept(text = FhirString(value = "conflict-test")),
    )

  private companion object {
    const val EDITED_ID = "sync-conflict-edited"
    const val FRESH_ID = "sync-conflict-fresh"
    const val SHARED_ID = "sync-conflict-shared"
  }
}
