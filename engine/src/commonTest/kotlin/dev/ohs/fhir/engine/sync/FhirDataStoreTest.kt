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

import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FhirDataStoreTest {
  private val fhirDataStore =
    FhirDataStore(getDataStore(testPlatformContext(), testStorageDirectory()))

  @Test
  fun observeTerminalSyncJobStatus_emitsTheWrittenStatus() = runTest {
    fhirDataStore.writeTerminalSyncJobStatus("key", SyncJobStatus.Succeeded())

    assertIs<SyncJobStatus.Succeeded>(fhirDataStore.observeTerminalSyncJobStatus("key").first())
  }
}
