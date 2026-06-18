package dev.ohs.fhir.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError

/**
 * iOS implementation of [dev.ohs.fhir.sync.SyncScheduler] using the BackgroundTasks framework.
 *
 * Both [oneTimeTaskIdentifier] and [periodicTaskIdentifier] must be listed under
 * `BGTaskSchedulerPermittedIdentifiers` in the app's Info.plist.
 *
 * [registerTaskHandlers] must be called once, before the app finishes launching (in
 * `application(_:didFinishLaunchingWithOptions:)` or `App.init` for SwiftUI), before any call to
 * [runOneTimeSync] or [schedulePeriodicSync].
 *
 * Periodic sync re-arms itself by submitting the next [BGProcessingTaskRequest] from within the
 * task handler. The OS determines the exact fire time based on system conditions; the interval
 * from [PeriodicSyncConfiguration.repeat] is used as `earliestBeginDate`.
 */
class IosSyncScheduler(
  private val taskFactory: () -> FhirSyncTask,
  private val dataStore: DataStore<Preferences> = createDataStore(),
  val oneTimeTaskIdentifier: String = "$DEFAULT_TASK_PREFIX.oneTime",
  val periodicTaskIdentifier: String = "$DEFAULT_TASK_PREFIX.periodic",
) : SyncScheduler {

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val _oneTimeFlow = MutableSharedFlow<CurrentSyncJobStatus>(replay = 1, extraBufferCapacity = 16)
  private val _periodicFlow = MutableSharedFlow<PeriodicSyncJobStatus>(replay = 1, extraBufferCapacity = 16)

  private var periodicConfig: PeriodicSyncConfiguration? = null

  /**
   * Registers the BGTask handlers with [BGTaskScheduler]. Must be called once at app startup
   * before the first run loop iteration completes. Subsequent calls for the same identifiers have
   * no effect.
   */
  @OptIn(ExperimentalForeignApi::class)
  fun registerTaskHandlers() {
    BGTaskScheduler.shared.registerForTaskWithIdentifier(
      identifier = oneTimeTaskIdentifier,
      usingQueue = null,
    ) { bgTask ->
      (bgTask as? BGProcessingTask)?.let { handleTask(it, isOneTime = true) }
        ?: Logger.w { "IosSyncScheduler: unexpected BGTask type for oneTime identifier" }
    }

    BGTaskScheduler.shared.registerForTaskWithIdentifier(
      identifier = periodicTaskIdentifier,
      usingQueue = null,
    ) { bgTask ->
      (bgTask as? BGProcessingTask)?.let { handleTask(it, isOneTime = false) }
        ?: Logger.w { "IosSyncScheduler: unexpected BGTask type for periodic identifier" }
    }
  }

  private fun handleTask(task: BGProcessingTask, isOneTime: Boolean) {
    val taskName = if (isOneTime) oneTimeTaskIdentifier else periodicTaskIdentifier

    val job: Job = scope.launch {
      try {
        emitRunning(isOneTime, SyncJobStatus.Started())

        val result = taskFactory().runSync(
          taskName = taskName,
          dataStore = dataStore,
          onProgress = { status -> emitRunning(isOneTime, status) },
        )

        when (result) {
          is SyncJobStatus.Succeeded -> emitSucceeded(isOneTime, result.timestamp)
          is SyncJobStatus.Failed -> emitFailed(isOneTime, result.timestamp)
          else -> {}
        }

        task.setTaskCompletedWithSuccess(result is SyncJobStatus.Succeeded)
      } catch (_: CancellationException) {
        task.setTaskCompletedWithSuccess(false)
      } catch (e: Exception) {
        Logger.e(e) { "IosSyncScheduler: sync failed with unexpected exception" }
        task.setTaskCompletedWithSuccess(false)
      }

      if (!isOneTime) {
        periodicConfig?.let { schedulePeriodicRequest(it) }
      }
    }

    task.expirationHandler = { job.cancel() }
  }

  override suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus> {
    _oneTimeFlow.emit(CurrentSyncJobStatus.Enqueued)
    submitOneTimeRequest()
    return _oneTimeFlow
  }

  override suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus> {
    periodicConfig = config
    val lastStatus = dataStore.observeLastSyncJobStatus(periodicTaskIdentifier).firstOrNull()
    _periodicFlow.emit(PeriodicSyncJobStatus(lastSyncJobStatus = lastStatus, currentSyncJobStatus = CurrentSyncJobStatus.Enqueued))
    schedulePeriodicRequest(config)
    return _periodicFlow
  }

  override suspend fun cancelOneTimeSync() {
    BGTaskScheduler.shared.cancelTaskRequestWithIdentifier(oneTimeTaskIdentifier)
    _oneTimeFlow.emit(CurrentSyncJobStatus.Cancelled)
  }

  override suspend fun cancelPeriodicSync() {
    periodicConfig = null
    BGTaskScheduler.shared.cancelTaskRequestWithIdentifier(periodicTaskIdentifier)
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun submitOneTimeRequest() {
    val request = BGProcessingTaskRequest(oneTimeTaskIdentifier)
    request.requiresNetworkConnectivity = true
    submitRequest(request)
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun schedulePeriodicRequest(config: PeriodicSyncConfiguration) {
    val request = BGProcessingTaskRequest(periodicTaskIdentifier)
    request.requiresNetworkConnectivity =
      config.syncConstraints.requiredNetworkType != NetworkType.NOT_REQUIRED
    request.requiresExternalPower = config.syncConstraints.requiresCharging
    request.earliestBeginDate =
      NSDate.dateWithTimeIntervalSinceNow(config.repeat.interval.inWholeSeconds.toDouble())
    submitRequest(request)
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun submitRequest(request: BGProcessingTaskRequest) {
    memScoped {
      val error = alloc<ObjCObjectVar<NSError?>>()
      if (!BGTaskScheduler.shared.submitTaskRequest(request, error.ptr)) {
        Logger.w { "IosSyncScheduler: failed to submit BGTask — ${error.value?.localizedDescription}" }
      }
    }
  }

  private suspend fun emitRunning(isOneTime: Boolean, status: SyncJobStatus) {
    if (isOneTime) {
      _oneTimeFlow.emit(CurrentSyncJobStatus.Running(status))
    } else {
      _periodicFlow.emit(
        PeriodicSyncJobStatus(
          lastSyncJobStatus = null,
          currentSyncJobStatus = CurrentSyncJobStatus.Running(status),
        ),
      )
    }
  }

  private suspend fun emitSucceeded(isOneTime: Boolean, timestamp: kotlin.time.Instant) {
    if (isOneTime) {
      _oneTimeFlow.emit(CurrentSyncJobStatus.Succeeded(timestamp))
    } else {
      _periodicFlow.emit(
        PeriodicSyncJobStatus(
          lastSyncJobStatus = LastSyncJobStatus.Succeeded(timestamp),
          currentSyncJobStatus = CurrentSyncJobStatus.Succeeded(timestamp),
        ),
      )
    }
  }

  private suspend fun emitFailed(isOneTime: Boolean, timestamp: kotlin.time.Instant) {
    if (isOneTime) {
      _oneTimeFlow.emit(CurrentSyncJobStatus.Failed(timestamp))
    } else {
      _periodicFlow.emit(
        PeriodicSyncJobStatus(
          lastSyncJobStatus = LastSyncJobStatus.Failed(timestamp),
          currentSyncJobStatus = CurrentSyncJobStatus.Failed(timestamp),
        ),
      )
    }
  }

  companion object {
    const val DEFAULT_TASK_PREFIX = "dev.ohs.fhir.sync"
  }
}

