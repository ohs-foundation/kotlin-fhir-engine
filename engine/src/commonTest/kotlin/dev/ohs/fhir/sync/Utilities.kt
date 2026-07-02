/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
