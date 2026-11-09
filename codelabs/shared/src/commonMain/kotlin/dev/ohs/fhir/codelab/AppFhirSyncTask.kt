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
package dev.ohs.fhir.codelab

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.sync.ConflictResolver
import dev.ohs.fhir.engine.sync.DownloadWorkManager
import dev.ohs.fhir.engine.sync.FhirSyncTask
import dev.ohs.fhir.engine.sync.upload.UploadStrategy

/** Defines how the app syncs with the FHIR server, on every platform. */
class AppFhirSyncTask(private val platformContext: Any) : FhirSyncTask {
  // Add code to define the download manager, conflict resolver, FHIR Engine and upload strategy

  override fun getDownloadWorkManager(): DownloadWorkManager = TODO()

  override fun getConflictResolver(): ConflictResolver = TODO()

  override fun getFhirEngine(): FhirEngine = TODO()

  override fun getUploadStrategy(): UploadStrategy = TODO()
}
