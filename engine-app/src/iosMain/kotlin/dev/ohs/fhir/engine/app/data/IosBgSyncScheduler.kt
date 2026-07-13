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
import dev.ohs.fhir.engine.sync.FhirSyncTask
import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.fhir.engine.sync.runSync
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSError

internal class IosBgSyncScheduler(
  private val taskIdentifier: String,
  private val taskFactory: () -> FhirSyncTask,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val registered = AtomicInt(0)

  fun register() {
    if (!registered.compareAndSet(0, 1)) return
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
      taskIdentifier,
      usingQueue = null,
      launchHandler = { task -> handleTask(task as BGProcessingTask) },
    )
    Logger.d { "IosBgSyncScheduler: registered handler for $taskIdentifier" }
  }

  fun schedule() {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
    val request =
      BGProcessingTaskRequest(taskIdentifier).apply {
        requiresNetworkConnectivity = true
        requiresExternalPower = false
      }
    submitRequest(request)
  }

  fun cancel() {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
  }

  private fun handleTask(task: BGProcessingTask) {
    val mutex = Mutex()
    var completed = false

    val completeOnce: (Boolean) -> Unit = { success ->
      scope.launch {
        mutex.withLock {
          if (!completed) {
            completed = true
            task.setTaskCompletedWithSuccess(success)
            if (success) schedule()
          }
        }
      }
    }

    task.expirationHandler = {
      Logger.w { "IosBgSyncScheduler: task expired for $taskIdentifier" }
      completeOnce(false)
    }

    scope.launch {
      try {
        val status = taskFactory().runSync(taskName = taskIdentifier, onProgress = {})
        Logger.d { "IosBgSyncScheduler: sync completed with $status" }
        completeOnce(status is SyncJobStatus.Succeeded)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.e(e) { "IosBgSyncScheduler: sync failed" }
        completeOnce(false)
      }
    }
  }

  @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
  private fun submitRequest(request: BGProcessingTaskRequest) {
    try {
      memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val success = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
        if (!success) {
          val msg = error.value?.localizedDescription ?: "no error details"
          Logger.e { "IosBgSyncScheduler: submit failed ($msg)" }
        }
      }
    } catch (e: Exception) {
      Logger.e(e) { "IosBgSyncScheduler: exception submitting request" }
    }
  }
}
