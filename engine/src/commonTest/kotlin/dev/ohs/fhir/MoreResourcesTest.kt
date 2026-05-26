/*
 * Copyright 2025-2026 Google LLC
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

import com.google.fhir.model.r4.FhirDateTime
import com.google.fhir.model.r4.Id
import com.google.fhir.model.r4.Instant as FhirInstant
import com.google.fhir.model.r4.Meta
import com.google.fhir.model.r4.Patient
import com.google.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class MoreResourcesTest {
  @Test
  fun getResourceType() {
    assertEquals(ResourceType.Patient, getResourceType(Patient::class))
  }

  @Test
  fun `getResourceClass by name should return resource class`() {
    assertEquals(Patient::class, getResourceClass<Patient>("Patient"))
  }

  @Test
  fun `getResourceClass by resource type should return resource class`() {
    assertEquals(Patient::class, getResourceClass<Patient>(ResourceType.Patient))
  }

  @Test
  fun `updateMeta should update resource meta with given versionId and lastUpdated`() {
    val versionId = "1"
    val instantValue = Clock.System.now()
    val resource = Patient(id = "patient")

    val updated = resource.updateMeta(versionId, instantValue)

    assertEquals(versionId, updated.versionId)
    assertEquals(instantValue, updated.lastUpdated)
  }

  @Test
  fun `updateMeta should not change existing meta if new values are null`() {
    val versionId = "1"
    val instantValue = Clock.System.now()
    val resource =
      Patient(
        id = "patient",
        meta =
          Meta(
            versionId = Id(value = versionId),
            lastUpdated = FhirInstant(value = FhirDateTime.fromString(instantValue.toString())),
          ),
      )

    val updated = resource.updateMeta(null, null)

    assertEquals(versionId, updated.versionId)
    assertEquals(instantValue, updated.lastUpdated)
  }
}
