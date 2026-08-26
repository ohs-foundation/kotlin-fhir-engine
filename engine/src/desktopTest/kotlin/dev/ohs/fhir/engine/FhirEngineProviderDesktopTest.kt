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
 * Desktop-only [FhirEngineProvider] tests.
 *
 * Both assertions here are about a reset that actually closes and reopens the database, which only
 * holds where a real file-backed connection does. Web cannot do either half in one page: closing
 * terminates the SQLite Web Worker so every later call hangs instead of failing, and skipping the
 * close leaves the first worker holding the exclusive OPFS sync access handle so the reopen blocks
 * forever. Browser benchmarks must reload the page to get a cold engine.
 */
class FhirEngineProviderDesktopTest {

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.reset()
  }

  @Test
  fun create_afterReset_shouldFailBecauseDatabaseIsClosed() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    val engine = FhirEngineProvider.getInstance()
    engine.create(Patient(id = "reset_test_patient_1"))

    FhirEngineProvider.reset()

    val result = runCatching { engine.create(Patient(id = "reset_test_patient_2")) }
    assertTrue(
      result.isFailure,
      "The engine held across reset() is still usable, so its database was never closed.",
    )
  }

  @Test
  fun reset_thenInit_shouldReturnWorkingEngine() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    FhirEngineProvider.getInstance().create(Patient(id = "reset_test_patient"))

    FhirEngineProvider.reset()

    // The whole point of closing the database in reset(): the next init() must be able to reopen
    // it. Benchmarks do this between iterations, so a broken close/reopen cycle would strand every
    // run after the first.
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
