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

package dev.ohs.fhir

import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.db.impl.entities.LocalChangeEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class LocalChangeTest {

  @Test
  fun toLocalChange_withInsertEntity_returnsMatchingLocalChange() = runTest {
    val localChangeEntity =
      LocalChangeEntity(
        id = 1,
        resourceType = ResourceType.Patient.name,
        resourceUuid = Uuid.random(),
        resourceId = "Patient-001",
        type = LocalChangeEntity.Type.INSERT.value,
        payload =
          FhirR4Json().encodeToString(
            Patient(
              id = "Patient-001",
              name =
                listOf(
                  HumanName(
                    given = listOf(FhirString(value = "John")),
                    family = FhirString(value = "Doe"),
                  ),
                ),
            ),
          ),
        timestamp = Clock.System.now(),
      )

    val localChange = localChangeEntity.toLocalChange()
    assertEquals(localChange.token.ids.first(), localChangeEntity.id)
    assertEquals(localChange.resourceType, localChangeEntity.resourceType)
    assertEquals(localChange.resourceId, localChangeEntity.resourceId)
    assertEquals(localChange.timestamp, localChangeEntity.timestamp)
    assertEquals(localChange.type, LocalChange.Type.from(localChangeEntity.type))
    assertEquals(localChange.payload, localChangeEntity.payload)
    assertEquals(localChange.versionId, localChangeEntity.versionId)
  }
}
