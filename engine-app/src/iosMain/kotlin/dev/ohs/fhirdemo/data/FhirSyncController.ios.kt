package dev.ohs.fhirdemo.data

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.LastSyncJobStatus
import dev.ohs.fhir.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.sync.SyncJobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

private val syncScheduler: IosSyncScheduler by lazy {
    IosSyncScheduler(
        periodicSyncTaskIdentifier = "dev.ohs.fhirdemo.sync.periodic",
        oneTimeSyncTaskIdentifier = "dev.ohs.fhirdemo.sync.onetime",
        taskFactory = { DemoFhirSyncTask() },
    ).also { it.register() }
}

actual class FhirSyncController actual constructor(context: Any) {
    actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> = flow {
        emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
        syncScheduler.submitOneTimeSync()
        emit(CurrentSyncJobStatus.Succeeded(Clock.System.now()))
    }

    actual suspend fun cancelOneTimeSync() {
        syncScheduler.cancelAllPendingRequests()
    }

    actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> = flow {
        emit(
            PeriodicSyncJobStatus(
                lastSyncJobStatus = null,
                currentSyncJobStatus = CurrentSyncJobStatus.Running(SyncJobStatus.Started()),
            )
        )
        syncScheduler.submitPeriodicSync()
        emit(
            PeriodicSyncJobStatus(
                lastSyncJobStatus = LastSyncJobStatus.Succeeded(Clock.System.now()),
                currentSyncJobStatus = CurrentSyncJobStatus.Succeeded(Clock.System.now()),
            )
        )
    }

    actual suspend fun cancelPeriodicSync() {
        syncScheduler.cancelAllPendingRequests()
    }
}
