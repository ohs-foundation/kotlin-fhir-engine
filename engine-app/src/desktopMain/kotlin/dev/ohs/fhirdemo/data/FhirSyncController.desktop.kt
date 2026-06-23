package dev.ohs.fhirdemo.data

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.Sync
import kotlinx.coroutines.flow.Flow

actual class FhirSyncController actual constructor(context: Any) {
    actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> =
        Sync.oneTimeSync(taskFactory = { DemoFhirSyncTask() })

    actual suspend fun cancelOneTimeSync() {
        Sync.cancelOneTimeSync<DemoFhirSyncTask>()
    }
}
