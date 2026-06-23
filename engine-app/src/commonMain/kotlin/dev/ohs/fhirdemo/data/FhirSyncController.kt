package dev.ohs.fhirdemo.data

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import kotlinx.coroutines.flow.Flow

expect class FhirSyncController(context: Any) {
    suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus>
    suspend fun cancelOneTimeSync()
}
