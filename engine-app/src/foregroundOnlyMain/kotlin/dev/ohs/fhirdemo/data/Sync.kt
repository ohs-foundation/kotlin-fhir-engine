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
import dev.ohs.fhir.FhirEngineProvider
import dev.ohs.fhir.sync.BackoffPolicy
import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.FhirDataStore
import dev.ohs.fhir.sync.FhirSyncTask
import dev.ohs.fhir.sync.LastSyncJobStatus
import dev.ohs.fhir.sync.PeriodicSyncConfiguration
import dev.ohs.fhir.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.sync.RetryConfiguration
import dev.ohs.fhir.sync.SyncJobStatus
import dev.ohs.fhir.sync.defaultRetryConfiguration
import dev.ohs.fhir.sync.runSync
import dev.ohs.fhir.sync.syncDispatcher
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides foreground-only scheduling for FHIR sync jobs backed by Kotlin Coroutines, shared by
 * Desktop (JVM) and the web (js, wasmJs) — none of these platforms has a native OS background
 * scheduler (no WorkManager, no BGTaskScheduler).
 *
 * Sync runs only while the host process is alive: the JVM process on Desktop, or the browser tab on
 * the web. There is no background scheduling — the process/tab must remain open for the duration of
 * the sync, and for periodic sync to keep firing. For long-running syncs (up to 45 minutes), use
 * [syncTimeout] to ensure stalled operations eventually fail and get retried.
 *
 * **Why the browser tab must stay open:** the alternative on the web platform is a Service Worker
 * registered for the Periodic Background Sync API, which can run after a tab is closed. It was
 * deliberately not used here:
 * - It only ships in Chromium (Chrome/Edge) — no Firefox, no Safari.
 * - It requires the site to be installed as a PWA and pass a browser-determined site-engagement
 *   heuristic; registration can silently fail with no user-visible signal.
 * - The browser — not the app — decides the actual firing cadence, so a requested interval is only
 *   a hint; real-world delays of hours (or the sync never firing) are normal.
 * - It requires a separate execution context (the Service Worker) with message-passing back to the
 *   page, which is substantial added complexity for what this scheduler needs to do.
 *
 * A tab-open coroutine scheduler is fully cross-browser and has predictable timing, which matters
 * more here than surviving tab closure. A backgrounded/minimized tab keeps running (browsers
 * throttle timers while backgrounded but don't stop them); only closing the tab stops it — the same
 * "process must stay alive" constraint Desktop already has.
 *
 * Implement [FhirSyncTask] and pass a factory to [oneTimeSync] or [periodicSync]. The returned
 * [Flow] emits state transitions for the lifetime of the sync operation.
 *
 * ```kotlin
 * // One-time sync with 45-minute timeout
 * val statusFlow = Sync.oneTimeSync(
 *     taskFactory = { MyFhirSyncTask() },
 *     syncTimeout = 45.minutes,
 * )
 * statusFlow.collect { status -> /* handle state */ }
 *
 * // Periodic sync every 15 minutes, each attempt capped at 45 minutes
 * val periodicFlow = Sync.periodicSync(
 *     PeriodicSyncConfiguration(
 *         repeat = RepeatInterval(15.minutes),
 *         syncTimeout = 45.minutes,
 *     )
 * ) { MyFhirSyncTask() }
 * periodicFlow.collect { status -> /* handle state */ }
 *
 * // Cancel
 * Sync.cancelPeriodicSync<MyFhirSyncTask>()
 * ```
 */
internal object Sync {
  private val scope = CoroutineScope(SupervisorJob() + syncDispatcher)
  private val mutex = Mutex()
  private val activeSyncs = mutableMapOf<String, SyncHandle>()
  private val fhirDataStore: FhirDataStore by lazy { FhirEngineProvider.getFhirDataStore() }

  /**
   * Executes a one-time sync using [FhirSyncTask] instances created by [taskFactory].
   *
   * If a one-time sync for [T] is already active, the existing [Flow] is returned immediately
   * without starting a new job.
   *
   * @param taskFactory Creates a fresh [FhirSyncTask] for each attempt (including retries).
   * @param retryConfiguration Retry policy on failure, or null to disable retries.
   * @param syncTimeout Maximum duration for a single sync attempt. If exceeded, the attempt is
   *   treated as failed and subject to retry. `null` means no timeout.
   * @return A [Flow] of [CurrentSyncJobStatus] tracking the full sync lifecycle.
   */
  suspend inline fun <reified T : FhirSyncTask> oneTimeSync(
    noinline taskFactory: () -> T,
    retryConfiguration: RetryConfiguration? = defaultRetryConfiguration,
    syncTimeout: Duration? = null,
  ): Flow<CurrentSyncJobStatus> {
    val uniqueWorkName = createSyncUniqueName<T>("oneTimeSync")
    return runOneTimeSync(uniqueWorkName, taskFactory, retryConfiguration, syncTimeout)
  }

  /**
   * Schedules a recurring foreground sync using [FhirSyncTask] instances created by [taskFactory].
   *
   * The sync repeats at the interval specified in [periodicSyncConfiguration]. If a periodic sync
   * for [T] is already running, the existing combined [Flow] is returned. Cancel with
   * [cancelPeriodicSync].
   *
   * The cadence is wall-clock-based: the delay between cycle completions adjusts so the next cycle
   * starts [repeat.interval] after the previous one began, regardless of sync duration.
   *
   * @param periodicSyncConfiguration Repeat interval, retry policy, and optional sync timeout.
   * @param taskFactory Creates a fresh [FhirSyncTask] for each sync cycle (including retries).
   * @return A [Flow] of [PeriodicSyncJobStatus] combining the current and last-completed state.
   */
  suspend inline fun <reified T : FhirSyncTask> periodicSync(
    periodicSyncConfiguration: PeriodicSyncConfiguration,
    noinline taskFactory: () -> T,
  ): Flow<PeriodicSyncJobStatus> {
    val uniqueWorkName = createSyncUniqueName<T>("periodicSync")
    return runPeriodicSync(uniqueWorkName, periodicSyncConfiguration, taskFactory)
  }

  /** Cancels a pending or running one-time sync for [T]. No-op if none is active. */
  suspend inline fun <reified T : FhirSyncTask> cancelOneTimeSync() {
    cancelSync(createSyncUniqueName<T>("oneTimeSync"))
  }

  /** Cancels a running periodic sync for [T]. No-op if none is active. */
  suspend inline fun <reified T : FhirSyncTask> cancelPeriodicSync() {
    cancelSync(createSyncUniqueName<T>("periodicSync"))
  }

  /** Returns the timestamp of the last successful sync, or null if none has occurred. */
  suspend fun getLastSyncTimestamp(): Instant? = fhirDataStore.readLastSyncTimestamp()

  suspend fun runOneTimeSync(
    uniqueWorkName: String,
    taskFactory: () -> FhirSyncTask,
    retryConfiguration: RetryConfiguration?,
    syncTimeout: Duration? = null,
  ): Flow<CurrentSyncJobStatus> {
    mutex
      .withLock { activeSyncs[uniqueWorkName] }
      ?.takeIf { it.job.isActive }
      ?.let {
        return it.progressChannel
      }

    val statusFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
    storeUniqueWorkNameInDataStore(fhirDataStore, uniqueWorkName)

    statusFlow.emit(CurrentSyncJobStatus.Enqueued)

    val job =
      scope.launch {
        val maxRetries = retryConfiguration?.maxRetries ?: 0
        var attempt = 0
        var lastResult: SyncJobStatus = SyncJobStatus.Failed()

        while (attempt <= maxRetries) {
          if (attempt > 0) {
            delay(computeBackoffDelayMillis(retryConfiguration!!, attempt - 1).milliseconds)
          }
          statusFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
          lastResult =
            try {
              runSyncWithTimeout(taskFactory(), uniqueWorkName, syncTimeout) { syncJobStatus ->
                statusFlow.emit(CurrentSyncJobStatus.Running(syncJobStatus))
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Logger.e(e) { "One-time sync failed: ${e.message}" }
              SyncJobStatus.Failed()
            }
          if (lastResult is SyncJobStatus.Succeeded) break
          attempt++
        }

        when (lastResult) {
          is SyncJobStatus.Succeeded ->
            statusFlow.emit(CurrentSyncJobStatus.Succeeded(lastResult.timestamp))
          else ->
            statusFlow.emit(
              CurrentSyncJobStatus.Failed(
                (lastResult as? SyncJobStatus.Failed)?.timestamp ?: Clock.System.now(),
              ),
            )
        }
        removeUniqueWorkNameInDataStore(fhirDataStore, uniqueWorkName)
        mutex.withLock { activeSyncs.remove(uniqueWorkName) }
      }

    mutex.withLock { activeSyncs[uniqueWorkName] = SyncHandle(job, statusFlow) }
    return statusFlow
  }

  private suspend fun runSyncWithTimeout(
    task: FhirSyncTask,
    taskName: String?,
    syncTimeout: Duration?,
    onProgress: suspend (SyncJobStatus) -> Unit,
  ): SyncJobStatus {
    val call = suspend { task.runSync(taskName = taskName, onProgress = onProgress) }
    return if (syncTimeout != null) {
      withTimeoutOrNull(syncTimeout) { call() }
        ?: run {
          Logger.w { "Sync timed out after $syncTimeout" }
          SyncJobStatus.Failed()
        }
    } else {
      call()
    }
  }

  suspend fun runPeriodicSync(
    uniqueWorkName: String,
    config: PeriodicSyncConfiguration,
    taskFactory: () -> FhirSyncTask,
  ): Flow<PeriodicSyncJobStatus> {
    mutex
      .withLock { activeSyncs[uniqueWorkName] }
      ?.takeIf { it.job.isActive }
      ?.let { handle ->
        return combine(
          handle.progressChannel,
          fhirDataStore.observeTerminalSyncJobStatus(uniqueWorkName),
        ) { current, last ->
          PeriodicSyncJobStatus(
            lastSyncJobStatus = mapSyncJobStatusToLastSync(last),
            currentSyncJobStatus = current,
          )
        }
      }

    storeUniqueWorkNameInDataStore(fhirDataStore, uniqueWorkName)

    val currentStatusFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
    val lastStatusFlow = fhirDataStore.observeTerminalSyncJobStatus(uniqueWorkName)

    currentStatusFlow.emit(CurrentSyncJobStatus.Enqueued)

    val job =
      scope.launch {
        while (true) {
          val cycleStart = Clock.System.now()
          val maxRetries = config.retryConfiguration?.maxRetries ?: 0
          var attempt = 0
          var lastResult: SyncJobStatus = SyncJobStatus.Failed()

          while (attempt <= maxRetries) {
            if (attempt > 0) {
              delay(
                computeBackoffDelayMillis(config.retryConfiguration!!, attempt - 1).milliseconds,
              )
            }
            currentStatusFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
            lastResult =
              try {
                runSyncWithTimeout(taskFactory(), uniqueWorkName, config.syncTimeout) {
                  syncJobStatus ->
                  currentStatusFlow.emit(CurrentSyncJobStatus.Running(syncJobStatus))
                }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Logger.e(e) { "Periodic sync failed: ${e.message}" }
                SyncJobStatus.Failed()
              }
            if (lastResult is SyncJobStatus.Succeeded) break
            attempt++
          }

          when (lastResult) {
            is SyncJobStatus.Succeeded ->
              currentStatusFlow.emit(CurrentSyncJobStatus.Succeeded(lastResult.timestamp))
            else ->
              currentStatusFlow.emit(
                CurrentSyncJobStatus.Failed(
                  (lastResult as? SyncJobStatus.Failed)?.timestamp ?: Clock.System.now(),
                ),
              )
          }

          val elapsed = Clock.System.now() - cycleStart
          val remaining = (config.repeat.interval - elapsed).coerceAtLeast(Duration.ZERO)
          delay(remaining.inWholeMilliseconds.milliseconds)
          currentStatusFlow.emit(CurrentSyncJobStatus.Enqueued)
        }
      }

    mutex.withLock { activeSyncs[uniqueWorkName] = SyncHandle(job, currentStatusFlow) }

    return combine(currentStatusFlow, lastStatusFlow) { current, last ->
      PeriodicSyncJobStatus(
        lastSyncJobStatus = mapSyncJobStatusToLastSync(last),
        currentSyncJobStatus = current,
      )
    }
  }

  suspend fun cancelSync(uniqueWorkName: String) {
    val handle = mutex.withLock { activeSyncs[uniqueWorkName] }
    if (handle == null || !handle.job.isActive) {
      Logger.w { "No active sync found for: $uniqueWorkName" }
      return
    }
    handle.progressChannel.emit(CurrentSyncJobStatus.Cancelled)
    handle.job.cancel()
    mutex.withLock { activeSyncs.remove(uniqueWorkName) }
    if (fhirDataStore.fetchUniqueWorkName(uniqueWorkName) != null) {
      fhirDataStore.removeUniqueWorkName(uniqueWorkName)
    }
  }

  // `KClass.qualifiedName` isn't supported on Kotlin/JS, so `simpleName` is used here instead —
  // safe cross-platform since [FhirSyncTask] implementations are always named (never anonymous).
  inline fun <reified T : FhirSyncTask> createSyncUniqueName(syncType: String): String =
    "${T::class.simpleName}-$syncType"

  private fun mapSyncJobStatusToLastSync(status: SyncJobStatus?): LastSyncJobStatus? =
    status?.let {
      when (it) {
        is SyncJobStatus.Succeeded -> LastSyncJobStatus.Succeeded(it.timestamp)
        is SyncJobStatus.Failed -> LastSyncJobStatus.Failed(it.timestamp)
        else -> error("Unexpected non-terminal sync status in DataStore: $it")
      }
    }

  private suspend fun storeUniqueWorkNameInDataStore(
    fhirDataStore: FhirDataStore,
    uniqueWorkName: String,
  ) {
    if (fhirDataStore.fetchUniqueWorkName(uniqueWorkName) == null) {
      fhirDataStore.storeUniqueWorkName(key = uniqueWorkName, value = uniqueWorkName)
    }
  }

  private suspend fun removeUniqueWorkNameInDataStore(
    fhirDataStore: FhirDataStore,
    uniqueWorkName: String,
  ) {
    if (fhirDataStore.fetchUniqueWorkName(uniqueWorkName) != null) {
      fhirDataStore.removeUniqueWorkName(key = uniqueWorkName)
    }
  }

  private fun computeBackoffDelayMillis(config: RetryConfiguration, attempt: Int): Long {
    val baseDelayMs = config.backoffCriteria.backoffDelay.inWholeMilliseconds
    return when (config.backoffCriteria.backoffPolicy) {
      BackoffPolicy.EXPONENTIAL -> baseDelayMs * (1L shl attempt)
      BackoffPolicy.LINEAR -> baseDelayMs
    }
  }
}

private data class SyncHandle(
  val job: Job,
  val progressChannel: MutableSharedFlow<CurrentSyncJobStatus>,
)
