package dev.ohs.fhirdemo.data

import android.content.Context
import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.Sync
import kotlinx.coroutines.flow.Flow

actual class FhirSyncController actual constructor(context: Any) {
    private val ctx = context as Context

    actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> =
        Sync.oneTimeSync<DemoFhirSyncWorker>(ctx)

    actual suspend fun cancelOneTimeSync() {
        Sync.cancelOneTimeSync<DemoFhirSyncWorker>(ctx)
    }
}
