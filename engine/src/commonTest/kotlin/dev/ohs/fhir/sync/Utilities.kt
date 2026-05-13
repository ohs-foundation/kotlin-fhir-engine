package dev.ohs.fhir.sync

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.sync.download.DownloadRequest
import dev.ohs.fhir.sync.upload.request.BundleUploadRequest
import dev.ohs.fhir.sync.upload.request.UploadRequest
import dev.ohs.fhir.sync.upload.request.UrlUploadRequest

internal class BundleDataSource(val onPostBundle: suspend (BundleUploadRequest) -> Resource) :
    DataSource {

    override suspend fun download(downloadRequest: DownloadRequest): Resource {
        TODO("Not yet implemented")
    }

    override suspend fun upload(request: UploadRequest) =
        onPostBundle((request as BundleUploadRequest))
}

internal class UrlRequestDataSource(val onUrlRequestSend: suspend (UrlUploadRequest) -> Resource) :
    DataSource {

    override suspend fun download(downloadRequest: DownloadRequest): Resource {
        TODO("Not yet implemented")
    }

    override suspend fun upload(request: UploadRequest) =
        onUrlRequestSend((request as UrlUploadRequest))
}
