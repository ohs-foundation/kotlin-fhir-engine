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
package dev.ohs.fhir.engine.app.data

import android.content.Context
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.sync.CurrentSyncJobStatus
import dev.ohs.fhir.engine.sync.LastSyncJobStatus
import dev.ohs.fhir.engine.sync.PeriodicSyncConfiguration
import dev.ohs.fhir.engine.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.engine.sync.RepeatInterval
import dev.ohs.fhir.engine.sync.Sync
import dev.ohs.fhir.engine.sync.SyncJobStatus
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

actual class FhirSyncController actual constructor(context: Any) {
  private val ctx = context as Context

  actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> =
    Sync.oneTimeSync<DemoFhirSyncWorker>(ctx)

  actual suspend fun cancelOneTimeSync() {
    Sync.cancelOneTimeSync<DemoFhirSyncWorker>(ctx)
  }

  actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> =
    Sync.periodicSync<DemoFhirSyncWorker>(
      context = ctx,
      periodicSyncConfiguration =
        PeriodicSyncConfiguration(
          repeat = RepeatInterval(interval = 15.minutes),
        ),
    )

  actual suspend fun cancelPeriodicSync() {
    Sync.cancelPeriodicSync<DemoFhirSyncWorker>(ctx)
  }

  actual suspend fun lastPeriodicSyncStatus(): Flow<PeriodicSyncJobStatus> {
    val key = "${DemoFhirSyncWorker::class.java.name}-periodicSync"
    return FhirEngineProvider.getFhirDataStore().observeTerminalSyncJobStatus(key).map { status ->
      PeriodicSyncJobStatus(
        lastSyncJobStatus =
          when (status) {
            is SyncJobStatus.Succeeded -> LastSyncJobStatus.Succeeded(status.timestamp)
            is SyncJobStatus.Failed -> LastSyncJobStatus.Failed(status.timestamp)
            else -> null
          },
        currentSyncJobStatus = CurrentSyncJobStatus.Enqueued,
      )
    }
  }
}
