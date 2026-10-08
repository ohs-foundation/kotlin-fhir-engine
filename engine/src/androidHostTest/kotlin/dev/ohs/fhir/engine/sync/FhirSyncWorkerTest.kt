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
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FhirSyncWorkerTest {
  private lateinit var context: Context

  open class PassingPeriodicSyncWorker(appContext: Context, workerParams: WorkerParameters) :
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

  class FailingPeriodicSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    PassingPeriodicSyncWorker(appContext, workerParams) {
    override fun getDataSource(): DataSource? = TestFailingDatasource
  }

  class FailingPeriodicSyncWorkerWithoutDataSource(
    appContext: Context,
    workerParams: WorkerParameters,
  ) : PassingPeriodicSyncWorker(appContext, workerParams) {
    override fun getDataSource(): DataSource? = null
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    FhirEngineProvider.init(FhirEngineConfiguration(testMode = true), context)
  }

  @After
  fun tearDown() {
    FhirEngineProvider.clearInstance()
  }

  @Test
  fun doWork_successfulSync_returnsSuccess() {
    val result = runBlocking { worker<PassingPeriodicSyncWorker>(maxRetries = 1).doWork() }

    assertTrue(result is ListenableWorker.Result.Success)
  }

  @Test
  fun doWork_failedSyncWithZeroRetries_returnsFailure() {
    val result = runBlocking { worker<FailingPeriodicSyncWorker>(maxRetries = 0).doWork() }

    assertTrue(result is ListenableWorker.Result.Failure)
  }

  @Test
  fun doWork_failedSyncOnTheLastAllowedAttempt_returnsFailure() {
    val result = runBlocking {
      worker<FailingPeriodicSyncWorker>(maxRetries = 2, runAttemptCount = 2).doWork()
    }

    assertTrue(result is ListenableWorker.Result.Failure)
  }

  @Test
  fun doWork_failedSyncWithAttemptsLeft_returnsRetry() {
    val result = runBlocking { worker<FailingPeriodicSyncWorker>(maxRetries = 1).doWork() }

    assertEquals(ListenableWorker.Result.retry(), result)
  }

  @Test
  fun doWork_withoutDataSource_returnsFailureNamingTheError() {
    val result = runBlocking {
      worker<FailingPeriodicSyncWorkerWithoutDataSource>(maxRetries = 1, runAttemptCount = 2)
        .doWork()
    }

    assertTrue(result is ListenableWorker.Result.Failure)
    assertEquals(
      "java.lang.IllegalStateException",
      (result as ListenableWorker.Result.Failure).outputData.getString("error"),
    )
  }

  // A worker's first attempt is number 0, but the test builder starts at 1 unless told otherwise.
  private inline fun <reified W : ListenableWorker> worker(
    maxRetries: Int,
    runAttemptCount: Int = 0,
  ) =
    TestListenableWorkerBuilder<W>(
        context,
        inputData = Data.Builder().putInt(MAX_RETRIES_ALLOWED, maxRetries).build(),
        runAttemptCount = runAttemptCount,
      )
      .build()
}
