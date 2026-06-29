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
import dev.ohs.fhir.sync.LastSyncJobStatus
import dev.ohs.fhir.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.sync.SyncJobStatus
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class FhirSyncController actual constructor(context: Any) {
  actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> = flow {
    emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
    syncScheduler.submitOneTimeSync()
    emit(CurrentSyncJobStatus.Succeeded(Clock.System.now()))
  }

  actual suspend fun cancelOneTimeSync() {
    syncScheduler.cancelAllPendingRequests()
  }

  actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> = flow {
    emit(
      PeriodicSyncJobStatus(
        lastSyncJobStatus = null,
        currentSyncJobStatus = CurrentSyncJobStatus.Running(SyncJobStatus.Started()),
      ),
    )
    syncScheduler.submitPeriodicSync()
    emit(
      PeriodicSyncJobStatus(
        lastSyncJobStatus = LastSyncJobStatus.Succeeded(Clock.System.now()),
        currentSyncJobStatus = CurrentSyncJobStatus.Succeeded(Clock.System.now()),
      ),
    )
  }

  actual suspend fun cancelPeriodicSync() {
    syncScheduler.cancelAllPendingRequests()
  }
}
