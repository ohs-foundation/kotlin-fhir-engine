/*
 * Copyright 2025-2026 Open Health Stack Foundation
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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import dev.ohs.fhir.FhirEngine
import dev.ohs.fhir.sync.upload.UploadStrategy
import kotlinx.serialization.json.Json

/**
 * Handles FHIR data synchronization between local database and remote server.
 *
 * Extend this abstract [CoroutineWorker] and implement the abstract methods to define your specific
 * synchronization behavior. The custom worker class can then be used to schedule periodic
 * synchronization jobs using [Sync].
 */
abstract class FhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams), FhirSyncTask {

  abstract override fun getFhirEngine(): FhirEngine

  abstract override fun getDownloadWorkManager(): DownloadWorkManager

  abstract override fun getConflictResolver(): ConflictResolver

  abstract override fun getUploadStrategy(): UploadStrategy

  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun doWork(): Result {
    val result =
      try {
        runSync(
          taskName = inputData.getString(UNIQUE_WORK_NAME),
          onProgress = { setProgress(buildWorkData(it)) },
        )
      } catch (e: IllegalStateException) {
        return Result.failure(buildErrorData(e))
      }

    val output = buildWorkData(result)
    Logger.d { "Received result from worker $result and sending output $output" }

    /**
     * In case of failure, we can check if its worth retrying and do retry based on
     * [RetryConfiguration.maxRetries] set by user.
     */
    val retries = inputData.getInt(MAX_RETRIES_ALLOWED, 0)
    return when (result) {
      is SyncJobStatus.Succeeded -> Result.success(output)
      else -> {
        if (retries > runAttemptCount) Result.retry() else Result.failure(output)
      }
    }
  }

  private fun buildWorkData(state: SyncJobStatus): Data {
    return workDataOf(
      "StateType" to state::class.java.name,
      "State" to json.encodeToString(state),
    )
  }

  private fun buildErrorData(exception: Exception): Data {
    return workDataOf("error" to exception::class.java.name, "reason" to exception.message)
  }
}
