package dev.ohs.fhirdemo.data

import dev.ohs.fhir.FhirEngine
import dev.ohs.fhir.sync.AcceptLocalConflictResolver
import dev.ohs.fhir.sync.ConflictResolver
import dev.ohs.fhir.sync.DownloadWorkManager
import dev.ohs.fhir.sync.FhirSyncTask
import dev.ohs.fhir.sync.upload.HttpCreateMethod
import dev.ohs.fhir.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.sync.upload.UploadStrategy

class DemoFhirSyncTask : FhirSyncTask {
    override fun getFhirEngine(): FhirEngine = fhirEngine()

    override fun getDownloadWorkManager(): DownloadWorkManager =
        TimestampBasedDownloadWorkManagerImpl(DemoDataStore(createDemoDataStore()))

    override fun getConflictResolver(): ConflictResolver = AcceptLocalConflictResolver

    override fun getUploadStrategy(): UploadStrategy =
        UploadStrategy.forBundleRequest(
            methodForCreate = HttpCreateMethod.PUT,
            methodForUpdate = HttpUpdateMethod.PATCH,
            squash = true,
            bundleSize = 500,
        )
}
