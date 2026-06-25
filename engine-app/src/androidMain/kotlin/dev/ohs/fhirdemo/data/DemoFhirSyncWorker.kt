package dev.ohs.fhirdemo.data

import android.content.Context
import androidx.work.WorkerParameters
import dev.ohs.fhir.sync.AcceptLocalConflictResolver
import dev.ohs.fhir.sync.DownloadWorkManager
import dev.ohs.fhir.sync.FhirSyncWorker
import dev.ohs.fhir.sync.upload.HttpCreateMethod
import dev.ohs.fhir.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.sync.upload.UploadStrategy
import dev.ohs.fhirdemo.DemoApplication

class DemoFhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    FhirSyncWorker(appContext, workerParams) {

    override fun getDownloadWorkManager(): DownloadWorkManager {
        return TimestampBasedDownloadWorkManagerImpl(DemoApplication.dataStore(applicationContext))
    }

    override fun getConflictResolver() = AcceptLocalConflictResolver

    override fun getUploadStrategy(): UploadStrategy =
        UploadStrategy.forBundleRequest(
            methodForCreate = HttpCreateMethod.PUT,
            methodForUpdate = HttpUpdateMethod.PATCH,
            squash = true,
            bundleSize = 500,
        )

    override fun getFhirEngine() = DemoApplication.fhirEngine(applicationContext)
}
