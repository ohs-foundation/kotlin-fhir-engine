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

import android.content.Context
import androidx.work.WorkerParameters
import dev.ohs.fhir.engine.sync.FhirSyncWorker

/** Runs [AppFhirSyncTask] with WorkManager. */
class AppFhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
  FhirSyncWorker(appContext, workerParams) {
  private val task = AppFhirSyncTask(appContext)

  override fun getDownloadWorkManager() = task.getDownloadWorkManager()

  override fun getConflictResolver() = task.getConflictResolver()

  override fun getFhirEngine() = task.getFhirEngine()

  override fun getUploadStrategy() = task.getUploadStrategy()
}
