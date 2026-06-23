/*
 * Copyright 2025-2026 Google LLC
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

package dev.ohs.fhir.sync

import co.touchlab.kermit.Logger
import dev.ohs.fhir.FhirEngineProvider.getFhirDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Provides in-process (foreground) scheduling for FHIR sync jobs backed by Kotlin Coroutines.
 *
 * For **background** sync that survives app suspension, use [IosSyncScheduler] which bridges to
 * iOS BGTaskScheduler.
 *
 * Implement [FhirSyncTask] and pass a factory to [oneTimeSync] or [periodicSync]. The returned
 * [Flow] emits state transitions for the lifetime of the sync operation.
 *
 * ```kotlin
 * // One-time sync
 * val statusFlow = Sync.oneTimeSync { MyFhirSyncTask() }
 * statusFlow.collect { status -> /* handle state */ }
 *
 * // Periodic sync every 15 minutes (foreground only)
 * val periodicFlow = Sync.periodicSync(
 *     PeriodicSyncConfiguration(repeat = RepeatInterval(15.minutes))
 * ) { MyFhirSyncTask() }
 * periodicFlow.collect { status -> /* handle state */ }
 *
 * // Cancel
 * Sync.cancelPeriodicSync<MyFhirSyncTask>()
 * ```
 */
object Sync {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val activeSyncs = mutableMapOf<String, SyncHandle>()

  /**
   * Executes a one-time foreground sync using [FhirSyncTask] instances created by [taskFactory].
   *
   * For background sync that survives app suspension, use [IosSyncScheduler.submitOneTimeSync]
   * instead.
   *
   * If a one-time sync for [T] is already active, the existing [Flow] is returned immediately
   * without starting a new job.
   *
   * @param taskFactory Creates a fresh [FhirSyncTask] for each attempt (including retries).
   * @param retryConfiguration Retry policy on failure, or null to disable retries.
   * @return A [Flow] of [CurrentSyncJobStatus] tracking the full sync lifecycle.
   */
  suspend inline fun <reified T : FhirSyncTask> oneTimeSync(
    noinline taskFactory: () -> T,
    retryConfiguration: RetryConfiguration? = defaultRetryConfiguration,
  ): Flow<CurrentSyncJobStatus> {
    val uniqueWorkName = createSyncUniqueName<T>("oneTimeSync")
    return runOneTimeSync(uniqueWorkName, taskFactory, retryConfiguration)
  }

  /**
   * Schedules a recurring foreground sync using [FhirSyncTask] instances created by [taskFactory].
   *
   * The sync repeats at the interval specified in [periodicSyncConfiguration] only while the
   * app is in the foreground. For background periodic sync that survives app suspension, use
   * [IosSyncScheduler.submitPeriodicSync] instead.
   *
   * If a periodic sync for [T] is already running, the existing combined [Flow] is returned. Cancel
   * with [cancelPeriodicSync].
   *
   * @param periodicSyncConfiguration Repeat interval and retry policy.
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
  suspend fun getLastSyncTimestamp(): Instant? = getFhirDataStore().readLastSyncTimestamp()

  @PublishedApi
  internal suspend fun runOneTimeSync(
    uniqueWorkName: String,
    taskFactory: () -> FhirSyncTask,
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    mutex.withLock { activeSyncs[uniqueWorkName] }
      ?.takeIf { it.job.isActive }
      ?.let { return it.progressChannel }

    val statusFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
    val dataStore = createDataStore()
    val fhirDataStore = FhirDataStore(dataStore)
    storeUniqueWorkNameInDataStore(fhirDataStore, uniqueWorkName)

    statusFlow.emit(CurrentSyncJobStatus.Enqueued)

    val job = scope.launch {
      val maxRetries = retryConfiguration?.maxRetries ?: 0
      var attempt = 0
      var lastResult: SyncJobStatus = SyncJobStatus.Failed()

      while (attempt <= maxRetries) {
        if (attempt > 0) {
          delay(computeBackoffDelayMillis(retryConfiguration!!, attempt - 1))
        }
        statusFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
        lastResult =
          try {
            taskFactory()
              .runSync(
                taskName = uniqueWorkName,
                dataStore = dataStore,
                onProgress = { statusFlow.emit(CurrentSyncJobStatus.Running(it)) },
              )
          } catch (e: IllegalStateException) {
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
              (lastResult as? SyncJobStatus.Failed)?.timestamp ?: Clock.System.now()
            )
          )
      }
      removeUniqueWorkNameInDataStore(fhirDataStore, uniqueWorkName)
      mutex.withLock { activeSyncs.remove(uniqueWorkName) }
    }

    mutex.withLock { activeSyncs[uniqueWorkName] = SyncHandle(job, statusFlow) }
    return statusFlow
  }

  @PublishedApi
  internal suspend fun runPeriodicSync(
    uniqueWorkName: String,
    config: PeriodicSyncConfiguration,
    taskFactory: () -> FhirSyncTask,
  ): Flow<PeriodicSyncJobStatus> {
    mutex.withLock { activeSyncs[uniqueWorkName] }
      ?.takeIf { it.job.isActive }
      ?.let { handle ->
        val dataStore = createDataStore()
        val fhirDataStore = FhirDataStore(dataStore)
        return combine(handle.progressChannel, fhirDataStore.observeTerminalSyncJobStatus(uniqueWorkName)) { current, last ->
          PeriodicSyncJobStatus(
            lastSyncJobStatus = mapSyncJobStatusToLastSync(last),
            currentSyncJobStatus = current,
          )
        }
      }

    val dataStore = createDataStore()
    val fhirDataStore = FhirDataStore(dataStore)
    storeUniqueWorkNameInDataStore(fhirDataStore, uniqueWorkName)

    val currentStatusFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1)
    val lastStatusFlow = fhirDataStore.observeTerminalSyncJobStatus(uniqueWorkName)

    currentStatusFlow.emit(CurrentSyncJobStatus.Enqueued)

    val job = scope.launch {
      while (true) {
        val maxRetries = config.retryConfiguration?.maxRetries ?: 0
        var attempt = 0
        var lastResult: SyncJobStatus = SyncJobStatus.Failed()

        while (attempt <= maxRetries) {
          if (attempt > 0) {
            delay(computeBackoffDelayMillis(config.retryConfiguration!!, attempt - 1))
          }
          currentStatusFlow.emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
          lastResult =
            try {
              taskFactory()
                .runSync(
                  taskName = uniqueWorkName,
                  dataStore = dataStore,
                  onProgress = { currentStatusFlow.emit(CurrentSyncJobStatus.Running(it)) },
                )
            } catch (e: IllegalStateException) {
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
                (lastResult as? SyncJobStatus.Failed)?.timestamp ?: Clock.System.now()
              )
            )
        }

        delay(config.repeat.interval.inWholeMilliseconds)
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

  @PublishedApi
  internal inline fun <reified T : FhirSyncTask> createSyncUniqueName(syncType: String): String =
    "${T::class.qualifiedName ?: T::class.simpleName}-$syncType"

  @PublishedApi
  internal suspend fun cancelSync(uniqueWorkName: String) {
    val handle = mutex.withLock { activeSyncs[uniqueWorkName] }
    if (handle == null || !handle.job.isActive) {
      Logger.w { "No active sync found for: $uniqueWorkName" }
      return
    }
    handle.progressChannel.emit(CurrentSyncJobStatus.Cancelled)
    handle.job.cancel()
    mutex.withLock { activeSyncs.remove(uniqueWorkName) }
    val fhirDataStore = getFhirDataStore()
    if (fhirDataStore.fetchUniqueWorkName(uniqueWorkName) != null) {
      fhirDataStore.removeUniqueWorkName(uniqueWorkName)
    }
  }

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

/**
 *
 * // Implement FhirSyncTask
 * class MyFhirSyncTask : FhirSyncTask {
 *     override fun getFhirEngine() = FhirEngineProvider.getInstance()
 *     override fun getDownloadWorkManager() = MyDownloadWorkManager()
 *     override fun getConflictResolver() = AcceptRemoteConflictResolver
 *     override fun getUploadStrategy() = UploadStrategy.forBundleRequest(...)
 * }
 *
 * // One-time sync
 * val flow = Sync.oneTimeSync { MyFhirSyncTask() }
 *
 * // Periodic sync (every 15 minutes)
 * val flow = Sync.periodicSync(
 *     PeriodicSyncConfiguration(repeat = RepeatInterval(15.minutes))
 * ) { MyFhirSyncTask() }
 *
 * // Cancel
 * Sync.cancelPeriodicSync<MyFhirSyncTask>()
 */

