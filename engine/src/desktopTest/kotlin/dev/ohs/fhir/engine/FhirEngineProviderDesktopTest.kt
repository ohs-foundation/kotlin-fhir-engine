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
package dev.ohs.fhir.engine

import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Desktop-only [FhirEngineProvider] tests. On web, a closed database hangs instead of failing, and
 * the same page cannot reopen it.
 */
class FhirEngineProviderDesktopTest {

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.resetForTesting()
  }

  @Test
  fun create_afterReset_shouldFailBecauseDatabaseIsClosed() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    val engine = FhirEngineProvider.getInstance()
    engine.create(Patient(id = "reset_test_patient_1"))

    FhirEngineProvider.resetForTesting()

    val result = runCatching { engine.create(Patient(id = "reset_test_patient_2")) }
    assertTrue(
      result.isFailure,
      "The engine held across resetForTesting() is still usable, so its database was never closed.",
    )
  }

  @Test
  fun resetForTesting_thenInit_shouldReturnWorkingEngine() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    FhirEngineProvider.getInstance().create(Patient(id = "reset_test_patient"))

    FhirEngineProvider.resetForTesting()

    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    val engine = FhirEngineProvider.getInstance()
    engine.create(Patient(id = "reset_test_patient_2"))
    assertEquals(
      "reset_test_patient_2",
      engine.get(ResourceType.Patient, "reset_test_patient_2").id,
    )
  }
}
