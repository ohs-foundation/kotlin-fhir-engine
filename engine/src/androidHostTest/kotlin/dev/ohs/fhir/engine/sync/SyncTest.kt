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
package dev.ohs.fhir.engine.sync

import android.content.Context
import androidx.work.WorkerParameters
import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncTest {
  class PassingPeriodicSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    FhirSyncWorker(appContext, workerParams) {
    override fun getFhirEngine(): FhirEngine = TestFhirEngineImpl

    override fun getDataSource(): DataSource? = TestDataSourceImpl

    override fun getDownloadWorkManager(): DownloadWorkManager = TestDownloadManagerImpl()

    override fun getConflictResolver() = AcceptRemoteConflictResolver

    override fun getUploadStrategy(): UploadStrategy =
      UploadStrategy.forBundleRequest(
        methodForCreate = HttpCreateMethod.PUT,
        methodForUpdate = HttpUpdateMethod.PATCH,
        squash = true,
        bundleSize = 500,
      )
  }

  private val retryConfiguration =
    RetryConfiguration(BackoffCriteria(BackoffPolicy.LINEAR, 30.seconds), 3)

  @Test
  fun createOneTimeWorkRequest_withRetryConfiguration_keepsBackoffAndMaxRetries() {
    val workRequest =
      Sync.createOneTimeWorkRequest(
        retryConfiguration,
        PassingPeriodicSyncWorker::class.java,
        "unique-name",
      )

    assertEquals(androidx.work.BackoffPolicy.LINEAR, workRequest.workSpec.backoffPolicy)
    assertEquals(30_000L, workRequest.workSpec.backoffDelayDuration)
    assertEquals(3, workRequest.workSpec.input.getInt(MAX_RETRIES_ALLOWED, 0))
  }

  @Test
  fun createOneTimeWorkRequest_withoutRetryConfiguration_hasZeroMaxRetries() {
    val workRequest =
      Sync.createOneTimeWorkRequest(null, PassingPeriodicSyncWorker::class.java, "unique-name")

    assertEquals(0, workRequest.workSpec.input.getInt(MAX_RETRIES_ALLOWED, 0))
  }

  @Test
  fun createPeriodicWorkRequest_withRetryConfiguration_keepsIntervalBackoffAndMaxRetries() {
    val workRequest =
      Sync.createPeriodicWorkRequest(
        PeriodicSyncConfiguration(
          repeat = RepeatInterval(20.minutes),
          retryConfiguration = retryConfiguration,
        ),
        PassingPeriodicSyncWorker::class.java,
        "unique-name",
      )

    assertEquals(20 * 60_000L, workRequest.workSpec.intervalDuration)
    assertEquals(androidx.work.BackoffPolicy.LINEAR, workRequest.workSpec.backoffPolicy)
    assertEquals(30_000L, workRequest.workSpec.backoffDelayDuration)
    assertEquals(3, workRequest.workSpec.input.getInt(MAX_RETRIES_ALLOWED, 0))
  }

  @Test
  fun createPeriodicWorkRequest_withoutRetryConfiguration_hasZeroMaxRetries() {
    val workRequest =
      Sync.createPeriodicWorkRequest(
        PeriodicSyncConfiguration(repeat = RepeatInterval(20.minutes), retryConfiguration = null),
        PassingPeriodicSyncWorker::class.java,
        "unique-name",
      )

    assertEquals(20 * 60_000L, workRequest.workSpec.intervalDuration)
    assertEquals(0, workRequest.workSpec.input.getInt(MAX_RETRIES_ALLOWED, 0))
  }
}
