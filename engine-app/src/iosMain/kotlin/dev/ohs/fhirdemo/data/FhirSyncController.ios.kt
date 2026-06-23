package dev.ohs.fhirdemo.data

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.IosSyncScheduler
import dev.ohs.fhir.sync.SyncJobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

actual class FhirSyncController actual constructor(context: Any) {
    private val scheduler = IosSyncScheduler(
        periodicSyncTaskIdentifier = "dev.ohs.fhirdemo.sync.periodic",
        oneTimeSyncTaskIdentifier = "dev.ohs.fhirdemo.sync.onetime",
        taskFactory = { DemoFhirSyncTask() },
    ).also { it.register() }

    actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> = flow {
        emit(CurrentSyncJobStatus.Running(SyncJobStatus.Started()))
        scheduler.submitOneTimeSync()
        emit(CurrentSyncJobStatus.Succeeded(Clock.System.now()))
    }

    actual suspend fun cancelOneTimeSync() {
        scheduler.cancelAllPendingRequests()
    }
}
