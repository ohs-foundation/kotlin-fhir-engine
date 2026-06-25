package dev.ohs.fhirdemo.data

import android.content.Context
import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.PeriodicSyncConfiguration
import dev.ohs.fhir.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.sync.RepeatInterval
import dev.ohs.fhir.sync.Sync
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.minutes

actual class FhirSyncController actual constructor(context: Any) {
    private val ctx = context as Context

    actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> =
        Sync.oneTimeSync<DemoFhirSyncWorker>(ctx)

    actual suspend fun cancelOneTimeSync() {
        Sync.cancelOneTimeSync<DemoFhirSyncWorker>(ctx)
    }

    actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> =
        Sync.periodicSync<DemoFhirSyncWorker>(
            context = ctx,
            periodicSyncConfiguration = PeriodicSyncConfiguration(
                repeat = RepeatInterval(interval = 15.minutes)
            ),
        )

    actual suspend fun cancelPeriodicSync() {
        Sync.cancelPeriodicSync<DemoFhirSyncWorker>(ctx)
    }
}
