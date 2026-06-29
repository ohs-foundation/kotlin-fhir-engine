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

import co.touchlab.kermit.Logger
import dev.ohs.fhir.sync.FhirSyncTask
import dev.ohs.fhir.sync.SyncJobStatus
import dev.ohs.fhir.sync.runSync
import kotlinx.cinterop.BetaInteropApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
import platform.Foundation.NSDate
import platform.Foundation.NSError

/**
 * Schedules and executes FHIR sync operations as iOS background tasks using [BGTaskScheduler].
 *
 * Background tasks allow long-running sync operations (up to 45 minutes) to continue when the app
 * is in the background. iOS determines the actual execution time based on system conditions; the
 * `earliestBeginDate` on a request is the earliest possible start, not a guarantee.
 *
 * ## Setup
 * 1. Add your task identifiers to `Info.plist` under `BGTaskSchedulerPermittedIdentifiers` as an
 *    array of strings.
 * 2. During app launch (`application(_:didFinishLaunchingWithOptions:)`), call [register] and
 *    [submitPeriodicSync].
 *
 * ```kotlin
 * val scheduler = IosSyncScheduler(
 *     periodicSyncTaskIdentifier = "com.example.fhir.sync",
 *     taskFactory = { MyFhirSyncTask() }
 * )
 * scheduler.register()
 * scheduler.submitPeriodicSync(interval = 15.minutes)
 * ```
 *
 * For user-initiated one-time syncs, call [submitOneTimeSync]:
 * ```kotlin
 * scheduler.submitOneTimeSync(earliestBeginDate = 30.seconds)
 * ```
 *
 * The scheduler automatically re-schedules each periodic sync cycle on completion.
 */
internal class IosSyncScheduler(
  private val periodicSyncTaskIdentifier: String,
  private val oneTimeSyncTaskIdentifier: String? = null,
  private val taskFactory: () -> FhirSyncTask,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var periodicInterval: Duration = 15.minutes
  private var registered = false

  /**
   * Registers BGTask launch handlers for the configured task identifiers.
   *
   * Must be called during app launch. Subsequent calls are no-ops.
   */
  fun register() {
    if (registered) return
    registered = true

    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
      periodicSyncTaskIdentifier,
      usingQueue = null,
      launchHandler = { task ->
        handleTask(task as BGProcessingTask, periodicSyncTaskIdentifier, ::reschedulePeriodic)
      },
    )

    oneTimeSyncTaskIdentifier?.let { identifier ->
      BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
        identifier,
        usingQueue = null,
        launchHandler = { task -> handleTask(task as BGProcessingTask, identifier) },
      )
    }

    Logger.d { "IosSyncScheduler: registered BGTask handlers" }
  }

  /**
   * Submits a one-time background sync request.
   *
   * @throws IllegalStateException if [oneTimeSyncTaskIdentifier] was not provided.
   */
  fun submitOneTimeSync(
    earliestBeginDate: Duration = Duration.ZERO,
    requiresNetworkConnectivity: Boolean = true,
  ) {
    val identifier =
      oneTimeSyncTaskIdentifier ?: error("oneTimeSyncTaskIdentifier was not provided")

    val request =
      BGProcessingTaskRequest(identifier).apply {
        this.requiresNetworkConnectivity = requiresNetworkConnectivity
        this.requiresExternalPower = false
        if (earliestBeginDate != Duration.ZERO) {
          this.earliestBeginDate = NSDate().plus(earliestBeginDate.inWholeSeconds.toDouble())
        }
      }
    submitRequest(request)
  }

  /**
   * Submits (or resubmits) a periodic background sync request.
   *
   * After each sync cycle the scheduler automatically re-schedules the next run at [interval] from
   * completion. Call once during app launch; subsequent calls update the interval and replace the
   * pending request.
   */
  fun submitPeriodicSync(
    interval: Duration = periodicInterval,
    earliestBeginDate: Duration = Duration.ZERO,
    requiresNetworkConnectivity: Boolean = true,
    requiresExternalPower: Boolean = false,
  ) {
    periodicInterval = interval

    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(periodicSyncTaskIdentifier)

    val request =
      BGProcessingTaskRequest(periodicSyncTaskIdentifier).apply {
        this.requiresNetworkConnectivity = requiresNetworkConnectivity
        this.requiresExternalPower = requiresExternalPower
        if (earliestBeginDate != Duration.ZERO) {
          this.earliestBeginDate = NSDate().plus(earliestBeginDate.inWholeSeconds.toDouble())
        }
      }
    submitRequest(request)
  }

  /** Cancels all pending BGTask requests for the configured identifiers. */
  fun cancelAllPendingRequests() {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(periodicSyncTaskIdentifier)
    oneTimeSyncTaskIdentifier?.let {
      BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(it)
    }
  }

  private fun reschedulePeriodic() {
    submitPeriodicSync()
  }

  private fun handleTask(
    task: BGProcessingTask,
    taskName: String?,
    onSuccess: () -> Unit = {},
  ) {
    val completeMutex = Mutex()
    var didCompleteTask = false

    val completeOnce: (Boolean) -> Unit = { success ->
      scope.launch {
        completeMutex.withLock {
          if (!didCompleteTask) {
            didCompleteTask = true
            task.setTaskCompletedWithSuccess(success)
            if (success) onSuccess()
          }
        }
      }
    }

    task.expirationHandler = {
      Logger.w { "IosSyncScheduler: BGTask expired for $taskName" }
      completeOnce(false)
    }

    scope.launch {
      val result = runCatching {
        taskFactory()
          .runSync(
            taskName = taskName,
            onProgress = {},
          )
      }

      result.onSuccess { status -> Logger.d { "IosSyncScheduler: sync completed for $taskName with status $status" } }
      result.onFailure { e -> Logger.e(e) { "IosSyncScheduler: sync failed for $taskName" } }

      completeOnce(result.getOrNull() is SyncJobStatus.Succeeded)
    }
  }

  @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
  private fun submitRequest(request: BGProcessingTaskRequest) {
    try {
      memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val success = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
        if (!success) {
          val errorMessage = error.value?.localizedDescription ?: "no error details"
          Logger.e { "IosSyncScheduler: submitTaskRequest failed ($errorMessage)" }
        }
      }
    } catch (e: Exception) {
      Logger.e(e) { "IosSyncScheduler: failed to submit BGTask request" }
    }
  }

  private fun NSDate.plus(seconds: Double): NSDate {
    return NSDate(timeIntervalSinceReferenceDate = this.timeIntervalSinceReferenceDate + seconds)
  }
}
