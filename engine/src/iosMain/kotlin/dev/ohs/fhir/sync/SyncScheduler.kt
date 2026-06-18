package dev.ohs.fhir.sync

import kotlinx.coroutines.flow.Flow

interface SyncScheduler {
  suspend fun runOneTimeSync(
    retryConfiguration: RetryConfiguration?,
  ): Flow<CurrentSyncJobStatus>

  suspend fun schedulePeriodicSync(
    config: PeriodicSyncConfiguration,
  ): Flow<PeriodicSyncJobStatus>

  suspend fun cancelOneTimeSync()

  suspend fun cancelPeriodicSync()
}