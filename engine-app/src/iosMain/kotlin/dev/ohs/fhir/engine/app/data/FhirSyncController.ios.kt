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

import co.touchlab.kermit.Logger
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.sync.CurrentSyncJobStatus
import dev.ohs.fhir.engine.sync.LastSyncJobStatus
import dev.ohs.fhir.engine.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.fhir.engine.sync.runSync
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

actual class FhirSyncController actual constructor(context: Any) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var currentJob: Job? = null

  @Volatile private var currentStatusFlow: MutableSharedFlow<CurrentSyncJobStatus>? = null

  @Volatile private var syncWasRunning = false

  init {
    NSNotificationCenter.defaultCenter.addObserverForName(
      UIApplicationDidEnterBackgroundNotification,
      null,
      null,
    ) { _ ->
      if (currentJob?.isActive == true) {
        syncWasRunning = true
        currentJob?.cancel()
        Logger.d { "FhirSyncController: sync suspended on background" }
      }
    }

    NSNotificationCenter.defaultCenter.addObserverForName(
      UIApplicationWillEnterForegroundNotification,
      null,
      null,
    ) { _ ->
      if (syncWasRunning) {
        syncWasRunning = false
        launchSyncJob()
        Logger.d { "FhirSyncController: sync restarted on foreground" }
      }
    }
  }

  actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> {
    val statusFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
    currentStatusFlow = statusFlow
    launchSyncJob()
    return statusFlow
  }

  actual suspend fun cancelOneTimeSync() {
    syncWasRunning = false
    currentJob?.cancel()
    currentJob = null
    currentStatusFlow = null
  }

  actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> {
    probeNetworkPermission()
    bgSyncScheduler.schedule()
    return FhirEngineProvider.getFhirDataStore()
      .observeTerminalSyncJobStatus(PERIODIC_SYNC_TASK_ID)
      .map { lastStatus ->
        PeriodicSyncJobStatus(
          lastSyncJobStatus = lastStatus.toLastSyncJobStatus(),
          currentSyncJobStatus = CurrentSyncJobStatus.Enqueued,
        )
      }
  }

  actual suspend fun cancelPeriodicSync() {
    bgSyncScheduler.cancel()
  }

  actual suspend fun lastPeriodicSyncStatus(): Flow<PeriodicSyncJobStatus> =
    FhirEngineProvider.getFhirDataStore().observeTerminalSyncJobStatus(PERIODIC_SYNC_TASK_ID).map {
      lastStatus ->
      PeriodicSyncJobStatus(
        lastSyncJobStatus = lastStatus.toLastSyncJobStatus(),
        currentSyncJobStatus = CurrentSyncJobStatus.Enqueued,
      )
    }

  // Fires a HEAD request in the foreground so iOS shows the wireless-data permission dialog
  // before the BGProcessingTask is scheduled (background tasks can't trigger the dialog).
  private suspend fun probeNetworkPermission() {
    val url = NSURL.URLWithString(SERVER_BASE_URL) ?: return
    val request = NSMutableURLRequest.requestWithURL(url).apply { setHTTPMethod("HEAD") }
    suspendCancellableCoroutine { cont ->
      val task =
        NSURLSession.sharedSession.dataTaskWithRequest(request) { _, _, _ -> cont.resume(Unit) }
      task.resume()
      cont.invokeOnCancellation { task.cancel() }
    }
  }

  private fun launchSyncJob() {
    val statusFlow = currentStatusFlow ?: return
    currentJob?.cancel()
    currentJob =
      scope.launch {
        statusFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
        val finalStatus: CurrentSyncJobStatus =
          try {
            val syncStatus =
              DemoFhirSyncTask()
                .runSync(
                  taskName = null,
                  onProgress = { syncJobStatus ->
                    statusFlow.emit(CurrentSyncJobStatus.Running(syncJobStatus))
                  },
                )
            when (syncStatus) {
              is SyncJobStatus.Succeeded -> CurrentSyncJobStatus.Succeeded(syncStatus.timestamp)
              else -> CurrentSyncJobStatus.Failed(Clock.System.now())
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Logger.e(e) { "FhirSyncController: one-time sync failed" }
            CurrentSyncJobStatus.Failed(Clock.System.now())
          }
        statusFlow.emit(finalStatus)
      }
  }
}

private fun SyncJobStatus?.toLastSyncJobStatus(): LastSyncJobStatus? =
  when (this) {
    is SyncJobStatus.Succeeded -> LastSyncJobStatus.Succeeded(timestamp)
    is SyncJobStatus.Failed -> LastSyncJobStatus.Failed(timestamp)
    else -> null
  }
