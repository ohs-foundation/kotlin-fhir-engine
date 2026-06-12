package dev.ohs.fhir.sync

import com.google.android.fhir.FhirEngine
import dev.ohs.fhir.sync.download.DownloaderImpl
import dev.ohs.fhir.sync.upload.UploadStrategy
import dev.ohs.fhir.sync.upload.Uploader
import dev.ohs.fhir.sync.upload.patch.PatchGeneratorFactory
import dev.ohs.fhir.sync.upload.request.UploadRequestGeneratorFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger as KermitLogger

/**
 * Platform-agnostic sync execution kernel.
 *
 * Builds the [FhirSynchronizer], drives the sync state machine, persists terminal status to
 * [FhirDataStore], and returns the final [SyncJobStatus]. All logic here is pure Kotlin coroutines
 * with no dependency on Android WorkManager or any other platform API.
 *
 */
internal class FhirSyncCore(
    private val fhirEngine: FhirEngine,
    private val dataSource: DataSource,
    private val downloadWorkManager: DownloadWorkManager,
    private val conflictResolver: ConflictResolver,
    private val uploadStrategy: UploadStrategy,
    private val fhirDataStore: FhirDataStore,
) {

    /**
     * Runs a full sync cycle (download then upload) and returns the terminal [SyncJobStatus].
     *
     * @param workerName Unique name used to persist the terminal status in [FhirDataStore]. Null when
     *   the sync was not scheduled via [Sync] (e.g. in tests or one-off invocations).
     * @param onProgress Called for every non-terminal [SyncJobStatus] emission so the caller can
     *   forward progress via its own signalling mechanism (e.g. WorkManager's `setProgress`).
     */
    suspend fun execute(
        workerName: String?,
        onProgress: suspend (SyncJobStatus) -> Unit,
    ): SyncJobStatus {
        val synchronizer =
            FhirSynchronizer(
                fhirEngine,
                UploadConfiguration(
                    uploader =
                        Uploader(
                            dataSource = dataSource,
                            patchGenerator = PatchGeneratorFactory.byMode(uploadStrategy.patchGeneratorMode),
                            requestGenerator =
                                UploadRequestGeneratorFactory.byMode(uploadStrategy.requestGeneratorMode),
                        ),
                    uploadStrategy = uploadStrategy,
                ),
                DownloadConfiguration(
                    DownloaderImpl(dataSource, downloadWorkManager),
                    conflictResolver,
                ),
                fhirDataStore,
            )

        val job =
            CoroutineScope(Dispatchers.IO).launch {
                synchronizer.syncState.collect { syncJobStatus ->
                    when (syncJobStatus) {
                        is SyncJobStatus.Succeeded,
                        is SyncJobStatus.Failed -> {
                            if (workerName != null) {
                                fhirDataStore.writeTerminalSyncJobStatus(workerName, syncJobStatus)
                            }
                            cancel()
                        }
                        else -> onProgress(syncJobStatus)
                    }
                }
            }

        val result = synchronizer.synchronize()
        kotlin.runCatching { job.join() }.onFailure { KermitLogger.w( throwable = it){ it.message ?: "" } }
        return result
    }
}