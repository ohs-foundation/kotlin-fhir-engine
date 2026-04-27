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

package dev.ohs.fhir.sync

import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirR4String
import kotlin.test.Test
import kotlin.test.assertIs


class AcceptRemoteConflictResolverTest {

  @Test
  fun resolve_shouldReturnRemoteChange() {
    val localResource =
      Patient(
        id = "patient-id-1",
        name =
          listOf(
            HumanName(
              family = FhirR4String(value = "Local"),
              given = listOf(FhirR4String(value = "Patient1")),
            ),
          ),
      )

    val remoteResource =
      Patient(
        id = "patient-id-1",
        name =
          listOf(
            HumanName(
              family = FhirR4String(value = "Remote"),
              given = listOf(FhirR4String(value = "Patient1")),
            ),
          ),
      )

    val result = AcceptRemoteConflictResolver.resolve(localResource, remoteResource)
    assertIs<Resolved>(result)
  }
}
