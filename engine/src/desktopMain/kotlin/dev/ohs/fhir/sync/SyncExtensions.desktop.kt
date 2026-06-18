package dev.ohs.fhir.sync

import kotlinx.coroutines.flow.Flow

/**
 * Starts a one-time sync on desktop using the provided [taskFactory].
 *
 * A new [DesktopSyncScheduler] is created for this call. If you need to cancel the sync later,
 * create the [DesktopSyncScheduler] directly and call [Sync.cancelOneTimeSync] on it.
 */
suspend fun Sync.oneTimeSync(
  taskFactory: () -> FhirSyncTask,
  retryConfiguration: RetryConfiguration? = defaultRetryConfiguration,
): Flow<CurrentSyncJobStatus> {
  val scheduler = DesktopSyncScheduler(taskFactory)
  return oneTimeSync(scheduler, retryConfiguration)
}

/**
 * Schedules a periodic sync on desktop using the provided [taskFactory].
 *
 * A new [DesktopSyncScheduler] is created for this call. If you need to cancel the sync later,
 * create the [DesktopSyncScheduler] directly and call [Sync.cancelPeriodicSync] on it.
 */
suspend fun Sync.periodicSync(
  taskFactory: () -> FhirSyncTask,
  periodicSyncConfiguration: PeriodicSyncConfiguration,
): Flow<PeriodicSyncJobStatus> {
  val scheduler = DesktopSyncScheduler(taskFactory)
  return periodicSync(scheduler, periodicSyncConfiguration)
}
