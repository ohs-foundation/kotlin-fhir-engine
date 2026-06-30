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
package dev.ohs.fhir.sync.upload

/**
 * Defines strategies for uploading FHIR resource [local changes][dev.ohs.fhir.LocalChange] to a
 * server during synchronization. It is used by the
 * [FhirSyncWorker][dev.ohs.fhir.sync.FhirSyncWorker] to determine the specific upload behavior.
 *
 * To specify an upload strategy, override
 * [getUploadStrategy][dev.ohs.fhir.sync.FhirSyncWorker.getUploadStrategy] in your app's
 * [FhirSyncWorker][dev.ohs.fhir.sync.FhirSyncWorker], for example:
 * ```kotlin
 * override fun getUploadStrategy(): UploadStrategy =
 *   UploadStrategy.forBundleRequest(methodForCreate = HttpCreateMethod.PUT, methodForUpdate = HttpUpdateMethod.PATCH, squash = true, bundleSize = 500)
 * ```
 *
 * The strategy you select depends on the server's capabilities (for example, support for `PUT` vs
 * `POST` requests), and your business requirements (for example, maintaining the history of every
 * local change).
 *
 * Each strategy specifies three key aspects of the upload process:
 * * **Fetching local changes**: This determines which local changes are included in the upload,
 *   specified by the [localChangesFetchMode] property.
 * * **Generating patches**: This determines how the local changes are represented for upload,
 *   specified by the `patchGeneratorMode` property.
 * * **Creating upload requests**: This determines how the patches are packaged and sent to the
 *   server, specified by the [requestGeneratorMode] property.
 *
 * Note: The strategies listed here represent all currently supported combinations of local change
 * fetching, patch generation, and upload request creation. Not all possible combinations of these
 * modes are valid or supported.
 */
class UploadStrategy
internal constructor(
  internal val localChangesFetchMode: Any,
  internal val requestGeneratorMode: Any,
)
