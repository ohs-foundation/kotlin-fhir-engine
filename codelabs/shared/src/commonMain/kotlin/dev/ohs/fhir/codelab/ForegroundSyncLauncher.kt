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
package dev.ohs.fhir.codelab

import dev.ohs.fhir.engine.sync.CurrentSyncJobStatus
import dev.ohs.fhir.engine.sync.FhirSyncTask
import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.fhir.engine.sync.runSync
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Runs the sync in the calling coroutine, for platforms without a background scheduler such as
 * WorkManager. The sync stops if the app closes.
 */
class ForegroundSyncLauncher(private val taskFactory: () -> FhirSyncTask) : SyncLauncher {
  @OptIn(ExperimentalTime::class)
  override suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> = channelFlow {
    send(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
    val result =
      try {
        taskFactory().runSync(taskName = null) { send(CurrentSyncJobStatus.Running(it)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SyncJobStatus.Failed(listOf())
      }
    send(
      if (result is SyncJobStatus.Succeeded) {
        CurrentSyncJobStatus.Succeeded(result.timestamp)
      } else {
        CurrentSyncJobStatus.Failed(Clock.System.now())
      },
    )
  }
}
