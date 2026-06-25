package dev.ohs.fhir.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.ohs.fhir.FhirEngine
import dev.ohs.fhir.FhirEngineProvider
import dev.ohs.fhir.sync.download.DownloaderImpl
import dev.ohs.fhir.sync.upload.UploadStrategy
import dev.ohs.fhir.sync.upload.Uploader
import dev.ohs.fhir.sync.upload.patch.PatchGeneratorFactory
import dev.ohs.fhir.sync.upload.request.UploadRequestGeneratorFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger as KermitLogger

/**
 *
 * Implement this interface to define the dependencies for a sync job. Each platform then wraps an
 * implementation in its own scheduling mechanism:
 * - **Android**: extend [FhirSyncWorker] (which implements this interface) and schedule via WorkManager
 * - **iOS**: pass a factory to `IosSyncScheduler` (in engine-app) to run as BGTask background jobs
 * - **Desktop**: use `Sync` (in engine-app) for coroutine-based foreground scheduling
 */
interface FhirSyncTask {
  fun getFhirEngine(): FhirEngine

  fun getDownloadWorkManager(): DownloadWorkManager

  fun getConflictResolver(): ConflictResolver

  fun getUploadStrategy(): UploadStrategy

}

/**
 * Executes a full sync cycle (download then upload) against [FhirSynchronizer] and returns the
 * terminal [SyncJobStatus].
 *
 * This is the single execution path shared by all platform schedulers.
 *
 * @param taskName Unique name used to persist the terminal status in [FhirDataStore]. Null when
 *   the sync was not scheduled via [Sync] (e.g. in tests or one-off invocations).
 * @param dataStore Persistence store for sync state and timestamps.
 * @param onProgress Called for every non-terminal [SyncJobStatus] emission so the caller can
 *   forward progress via its own signalling mechanism (e.g. WorkManager's `setProgress`).
 */
suspend fun FhirSyncTask.runSync(
  taskName: String?,
  dataStore: DataStore<Preferences>,
  onProgress: suspend (SyncJobStatus) -> Unit,
): SyncJobStatus {
  val fhirDataStore = FhirDataStore(dataStore)
  val dataSource =
    FhirEngineProvider.getDataSource()
      ?: throw IllegalStateException(
        "FhirEngineConfiguration.ServerConfiguration is not set. Call FhirEngineProvider.init to initialize with appropriate configuration.",
      )

  val uploadStrategy = getUploadStrategy()
  val synchronizer =
    FhirSynchronizer(
      getFhirEngine(),
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
        DownloaderImpl(dataSource, getDownloadWorkManager()),
        getConflictResolver(),
      ),
      fhirDataStore ,
    )

  return coroutineScope {
    launch(Dispatchers.IO) {
      synchronizer.syncState.collect { syncJobStatus ->
        when (syncJobStatus) {
          is SyncJobStatus.Succeeded,
          is SyncJobStatus.Failed -> {
            if (taskName != null) {
              fhirDataStore.writeTerminalSyncJobStatus(taskName, syncJobStatus)
            }
            cancel()
          }
          else -> onProgress(syncJobStatus)
        }
      }
    }

    synchronizer.synchronize()
  }
}
