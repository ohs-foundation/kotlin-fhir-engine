package dev.ohs.fhir.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Desktop (JVM) implementation of [SyncScheduler] backed by Kotlin coroutines.
 *
 * One-time sync runs immediately in the provided [scope]. Periodic sync loops with [delay]
 * between runs using the interval from [PeriodicSyncConfiguration.repeat]. Both respect the
 * [RetryConfiguration] via exponential or linear back-off before each retry attempt.
 *
 * The scheduler is stateful: hold a reference to it for the lifetime of the sync session so that
 * [cancelOneTimeSync] and [cancelPeriodicSync] can reach the running jobs.
 *
 * @param taskFactory Called once per sync attempt to obtain a fresh [FhirSyncTask] instance.
 * @param dataStore Persistence store for terminal sync status and timestamps.
 * @param scope Coroutine scope that owns the sync jobs. Defaults to a new
 *   [SupervisorJob]-backed scope on [Dispatchers.IO].
 */
class DesktopSyncScheduler(
  private val taskFactory: () -> FhirSyncTask,
  private val dataStore: DataStore<Preferences> = createDataStore(),
  private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : SyncScheduler {

  private var oneTimeJob: Job? = null
  private var periodicJob: Job? = null

  private val _oneTimeFlow =
    MutableSharedFlow<CurrentSyncJobStatus>(replay = 1, extraBufferCapacity = 16)
  private val _periodicFlow =
    MutableSharedFlow<PeriodicSyncJobStatus>(replay = 1, extraBufferCapacity = 16)

  /**
   * Runs a single sync immediately. If a one-time sync is already running it returns the existing
   * flow without starting a second job, matching Android's [ExistingWorkPolicy.KEEP] behaviour.
   */
  override suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    if (oneTimeJob?.isActive == true) return _oneTimeFlow

    _oneTimeFlow.emit(CurrentSyncJobStatus.Enqueued)
    oneTimeJob = scope.launch {
      val result =
        runWithRetry(
          taskName = TASK_NAME_ONE_TIME,
          retryConfig = retryConfiguration,
          onProgress = { status -> _oneTimeFlow.emit(CurrentSyncJobStatus.Running(status)) },
        )
      when (result) {
        is SyncJobStatus.Succeeded -> _oneTimeFlow.emit(CurrentSyncJobStatus.Succeeded(result.timestamp))
        else -> _oneTimeFlow.emit(CurrentSyncJobStatus.Failed(result.timestamp))
      }
    }
    return _oneTimeFlow
  }

  /**
   * Schedules a repeating sync loop. Each iteration waits for the sync to finish, emits the
   * terminal status, then delays for [PeriodicSyncConfiguration.repeat] before the next run.
   *
   * Emits an initial [PeriodicSyncJobStatus] with the last known status from [dataStore] so that
   * subscribers get a meaningful first value even before the first sync completes.
   */
  override suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus> {
    if (periodicJob?.isActive == true) return _periodicFlow

    val lastStatus = dataStore.observeLastSyncJobStatus(TASK_NAME_PERIODIC).firstOrNull()
    _periodicFlow.emit(
      PeriodicSyncJobStatus(
        lastSyncJobStatus = lastStatus,
        currentSyncJobStatus = CurrentSyncJobStatus.Enqueued,
      ),
    )

    periodicJob = scope.launch {
      while (isActive) {
        val result =
          runWithRetry(
            taskName = TASK_NAME_PERIODIC,
            retryConfig = config.retryConfiguration,
            onProgress = { status ->
              _periodicFlow.emit(
                PeriodicSyncJobStatus(
                  lastSyncJobStatus = null,
                  currentSyncJobStatus = CurrentSyncJobStatus.Running(status),
                ),
              )
            },
          )

        val (lastSync, currentSync) = when (result) {
          is SyncJobStatus.Succeeded ->
            LastSyncJobStatus.Succeeded(result.timestamp) to
              CurrentSyncJobStatus.Succeeded(result.timestamp)
          else ->
            LastSyncJobStatus.Failed(result.timestamp) to
              CurrentSyncJobStatus.Failed(result.timestamp)
        }
        _periodicFlow.emit(PeriodicSyncJobStatus(lastSync, currentSync))

        delay(config.repeat.interval)
      }
    }
    return _periodicFlow
  }

  override suspend fun cancelOneTimeSync() {
    oneTimeJob?.cancel()
    oneTimeJob = null
    _oneTimeFlow.emit(CurrentSyncJobStatus.Cancelled)
  }

  override suspend fun cancelPeriodicSync() {
    periodicJob?.cancel()
    periodicJob = null
  }

  private suspend fun runWithRetry(
    taskName: String,
    retryConfig: RetryConfiguration?,
    onProgress: suspend (SyncJobStatus) -> Unit,
  ): SyncJobStatus {
    val maxRetries = retryConfig?.maxRetries ?: 0
    var attempts = 0
    while (true) {
      val result = runCatching { taskFactory().runSync(taskName, dataStore, onProgress) }
        .getOrElse { e ->
          Logger.e(e) { "DesktopSyncScheduler: sync attempt ${attempts + 1} threw unexpectedly" }
          SyncJobStatus.Failed()
        }
      if (result is SyncJobStatus.Succeeded || attempts >= maxRetries) return result
      attempts++
      delay(backoffDelay(retryConfig, attempts))
    }
  }

  private fun backoffDelay(retryConfig: RetryConfiguration?, attempt: Int): Duration {
    val criteria = retryConfig?.backoffCriteria ?: return Duration.ZERO
    return when (criteria.backoffPolicy) {
      BackoffPolicy.LINEAR -> criteria.backoffDelay
      BackoffPolicy.EXPONENTIAL -> criteria.backoffDelay * (1 shl (attempt - 1))
    }
  }

  private companion object {
    const val TASK_NAME_ONE_TIME = "desktop-oneTimeSync"
    const val TASK_NAME_PERIODIC = "desktop-periodicSync"
  }
}
