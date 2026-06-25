package dev.ohs.fhirdemo.data

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.PeriodicSyncConfiguration
import dev.ohs.fhir.sync.PeriodicSyncJobStatus
import dev.ohs.fhir.sync.RepeatInterval
import dev.ohs.fhir.sync.Sync
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.minutes

actual class FhirSyncController actual constructor(context: Any) {
    actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> =
        Sync.oneTimeSync(taskFactory = { DemoFhirSyncTask() })

    actual suspend fun cancelOneTimeSync() {
        Sync.cancelOneTimeSync<DemoFhirSyncTask>()
    }

    actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> =
        Sync.periodicSync(
            periodicSyncConfiguration = PeriodicSyncConfiguration(
                repeat = RepeatInterval(interval = 15.minutes)
            ),
            taskFactory = { DemoFhirSyncTask() },
        )

    actual suspend fun cancelPeriodicSync() {
        Sync.cancelPeriodicSync<DemoFhirSyncTask>()
    }
}
