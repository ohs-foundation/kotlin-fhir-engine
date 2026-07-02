/*
 * Copyright 2023-2026 Open Health Stack Foundation
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
package dev.ohs.fhir.sync.upload.request

import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.LocalChangeToken
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.sync.upload.patch.Patch
import kotlin.time.Clock

internal object RequestGeneratorTestUtils {

  fun LocalChange.toPatch() =
    Patch(
      resourceType = resourceType,
      resourceId = resourceId,
      versionId = versionId,
      timestamp = timestamp,
      payload = payload,
      type = Patch.Type.from(type.value),
    )

  val jsonParser = FhirR4Json()
  val insertionLocalChange =
    LocalChange(
      resourceType = ResourceType.Patient.name,
      resourceId = "Patient-001",
      type = LocalChange.Type.INSERT,
      payload =
        jsonParser.encodeToString(
          Patient(
            id = "Patient-001",
            name =
              listOf(
                HumanName(
                  given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                  family = dev.ohs.fhir.model.r4.String(value = "Doe"),
                ),
              ),
          ),
        ),
      timestamp = Clock.System.now(),
      token = LocalChangeToken(listOf(1L)),
    )
  val updateLocalChange =
    LocalChange(
      resourceType = ResourceType.Patient.name,
      resourceId = "Patient-001",
      type = LocalChange.Type.UPDATE,
      payload = "[{\"op\":\"replace\",\"path\":\"\\/name\\/0\\/given\\/0\",\"value\":\"Janet\"}]",
      timestamp = Clock.System.now(),
      token = LocalChangeToken(listOf(2L)),
      versionId = "v-p002-01",
    )
  val deleteLocalChange =
    LocalChange(
      resourceType = ResourceType.Patient.name,
      resourceId = "Patient-001",
      type = LocalChange.Type.DELETE,
      payload =
        jsonParser.encodeToString(
          Patient(
            id = "Patient-001",
            name =
              listOf(
                HumanName(
                  given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                  family = dev.ohs.fhir.model.r4.String(value = "Doe"),
                ),
              ),
          ),
        ),
      timestamp = Clock.System.now(),
      token = LocalChangeToken(listOf(2L)),
      versionId = "v-p003-01",
    )
}
