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
package dev.ohs.fhirdemo.data

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.PeriodicSyncConfiguration
import dev.ohs.fhir.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.sync.RepeatInterval
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

actual class FhirSyncController actual constructor(context: Any) {
  actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> =
    Sync.oneTimeSync(taskFactory = { DemoFhirSyncTask() })

  actual suspend fun cancelOneTimeSync() {
    Sync.cancelOneTimeSync<DemoFhirSyncTask>()
  }

  actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> =
    Sync.periodicSync(
      periodicSyncConfiguration =
        PeriodicSyncConfiguration(
          repeat = RepeatInterval(interval = 15.minutes),
        ),
      taskFactory = { DemoFhirSyncTask() },
    )

  actual suspend fun cancelPeriodicSync() {
    Sync.cancelPeriodicSync<DemoFhirSyncTask>()
  }
}
