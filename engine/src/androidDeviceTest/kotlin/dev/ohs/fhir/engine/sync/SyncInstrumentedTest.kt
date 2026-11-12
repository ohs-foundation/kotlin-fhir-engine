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
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the sync workers through the test WorkManager on a device. */
class SyncInstrumentedTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  open class TestSyncWorker(appContext: Context, workerParams: WorkerParameters) :
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

  class TestSyncWorkerForDownloadFailing(appContext: Context, workerParams: WorkerParameters) :
    TestSyncWorker(appContext, workerParams) {
    override fun getDataSource(): DataSource? = TestFailingDatasource
  }

  private val noRetry = RetryConfiguration(BackoffCriteria(BackoffPolicy.LINEAR, 30.seconds), 0)
  private val every15Minutes = PeriodicSyncConfiguration(repeat = RepeatInterval(15.minutes))

  @BeforeTest
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    // Terminal sync results are kept in the DataStore under the work name, so clear them to keep
    // one test's result out of the next.
    runBlocking { createDataStore(context).edit { it.clear() } }
    FhirEngineProvider.init(FhirEngineConfiguration(testMode = true), context)
  }

  @AfterTest
  fun tearDown() {
    WorkManager.getInstance(context).cancelAllWork().result.get()
    FhirEngineProvider.resetForTesting()
  }

  @Test
  fun oneTimeSync_runsTheWorkerToSuccess(): Unit = runBlocking {
    Sync.oneTimeSync<TestSyncWorker>(context).untilOneTime { it is CurrentSyncJobStatus.Succeeded }

    val info = WorkManager.getInstance(context).getWorkInfosByTag(TestSyncWorker::class.java.name)
    assertEquals(WorkInfo.State.SUCCEEDED, info.get().first().state)
  }

  @Test
  fun oneTimeSync_reportsRunningThenSucceeded(): Unit = runBlocking {
    val states =
      Sync.oneTimeSync<TestSyncWorker>(context).untilOneTime {
        it is CurrentSyncJobStatus.Succeeded
      }

    assertIs<CurrentSyncJobStatus.Running>(states.firstRun())
    assertIs<CurrentSyncJobStatus.Succeeded>(states.last())
  }

  @Test
  fun oneTimeSync_afterASuccess_startsRunningAgain(): Unit = runBlocking {
    val states =
      Sync.oneTimeSync<TestSyncWorker>(context).untilOneTime {
        it is CurrentSyncJobStatus.Succeeded
      }
    val nextStates =
      Sync.oneTimeSync<TestSyncWorker>(context).untilOneTime {
        it is CurrentSyncJobStatus.Succeeded
      }

    assertIs<CurrentSyncJobStatus.Running>(states.firstRun())
    assertIs<CurrentSyncJobStatus.Succeeded>(states.last())
    assertIs<CurrentSyncJobStatus.Running>(nextStates.firstRun())
  }

  @Test
  fun oneTimeSync_failedWorker_reportsRunningThenFailed(): Unit = runBlocking {
    val states =
      Sync.oneTimeSync<TestSyncWorkerForDownloadFailing>(context, noRetry).untilOneTime {
        it is CurrentSyncJobStatus.Failed
      }

    assertIs<CurrentSyncJobStatus.Running>(states.firstRun())
    assertIs<CurrentSyncJobStatus.Failed>(states.last())
  }

  @Test
  fun oneTimeSync_afterAFailure_startsRunningAgain(): Unit = runBlocking {
    val states =
      Sync.oneTimeSync<TestSyncWorkerForDownloadFailing>(context, noRetry).untilOneTime {
        it is CurrentSyncJobStatus.Failed
      }
    val nextStates =
      Sync.oneTimeSync<TestSyncWorkerForDownloadFailing>(context, noRetry).untilOneTime {
        it is CurrentSyncJobStatus.Failed
      }

    assertIs<CurrentSyncJobStatus.Running>(states.firstRun())
    assertIs<CurrentSyncJobStatus.Failed>(states.last())
    assertIs<CurrentSyncJobStatus.Running>(nextStates.firstRun())
  }

  @Test
  fun periodicSync_reportsRunningThenEnqueuedWithTheLastResult(): Unit = runBlocking {
    val states =
      Sync.periodicSync<TestSyncWorker>(context, every15Minutes)
        .also { runPeriodicWorkNow<TestSyncWorker>() }
        .untilEnqueued()

    val firstRun = states.first { it.currentSyncJobStatus !is CurrentSyncJobStatus.Enqueued }
    assertIs<CurrentSyncJobStatus.Running>(firstRun.currentSyncJobStatus)
    assertNull(firstRun.lastSyncJobStatus)
    assertIs<CurrentSyncJobStatus.Enqueued>(states.last().currentSyncJobStatus)
    assertIs<LastSyncJobStatus.Succeeded>(states.last().lastSyncJobStatus)
  }

  @Test
  fun periodicSync_failedWorker_reportsEnqueuedWithAFailedLastResult(): Unit = runBlocking {
    val states =
      Sync.periodicSync<TestSyncWorkerForDownloadFailing>(context, every15Minutes)
        .also { runPeriodicWorkNow<TestSyncWorkerForDownloadFailing>() }
        .untilEnqueued()

    val firstRun = states.first { it.currentSyncJobStatus !is CurrentSyncJobStatus.Enqueued }
    assertIs<CurrentSyncJobStatus.Running>(firstRun.currentSyncJobStatus)
    assertNull(firstRun.lastSyncJobStatus)
    assertIs<CurrentSyncJobStatus.Enqueued>(states.last().currentSyncJobStatus)
    assertIs<LastSyncJobStatus.Failed>(states.last().lastSyncJobStatus)
  }

  @Test
  fun periodicSync_staysEnqueuedAfterAOneTimeSyncRuns(): Unit = runBlocking {
    val periodicSync = Sync.periodicSync<TestSyncWorker>(context, every15Minutes)
    runPeriodicWorkNow<TestSyncWorker>()
    val firstResult = periodicSync.untilEnqueued().last().lastSyncJobStatus!!

    Sync.oneTimeSync<TestSyncWorker>(context).untilOneTime { it is CurrentSyncJobStatus.Succeeded }

    val periodicWorkName = Sync.createSyncUniqueName<TestSyncWorker>("periodicSync")
    val workManager = WorkManager.getInstance(context)
    val periodicWork = workManager.getWorkInfosForUniqueWork(periodicWorkName).get().single()
    assertEquals(WorkInfo.State.ENQUEUED, periodicWork.state)

    // Advancing to the next period runs the periodic work again. The run can finish before its
    // RUNNING state is read, so wait for the newer result it records instead.
    runPeriodicWorkNow<TestSyncWorker>()
    withTimeoutOrNull(20_000) {
      periodicSync.first { status ->
        status.lastSyncJobStatus.let { it != null && it.timestamp > firstResult.timestamp }
      }
    }
      ?: fail("The periodic work did not run again")
  }

  /**
   * The test WorkManager runs periodic work only once its constraints and period are met, and the
   * constraints must be met again for every period.
   */
  private inline fun <reified W : FhirSyncWorker> runPeriodicWorkNow() {
    val workName = Sync.createSyncUniqueName<W>("periodicSync")
    val id = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get().single().id
    with(WorkManagerTestInitHelper.getTestDriver(context)!!) {
      setAllConstraintsMet(id)
      setPeriodDelayMet(id)
    }
  }

  /**
   * The first status of a run, skipping the Enqueued that comes first when collection starts before
   * the run does.
   */
  private fun List<CurrentSyncJobStatus>.firstRun() = first { it !is CurrentSyncJobStatus.Enqueued }

  /**
   * Collects one-time sync statuses until [isTerminal] holds, and fails if it does not within 20s.
   */
  private suspend fun Flow<CurrentSyncJobStatus>.untilOneTime(
    isTerminal: (CurrentSyncJobStatus) -> Boolean,
  ): List<CurrentSyncJobStatus> {
    val states = mutableListOf<CurrentSyncJobStatus>()
    withTimeoutOrNull(20_000) {
      transformWhile {
          emit(it)
          !isTerminal(it)
        }
        .collect { states.add(it) }
    }
      ?: fail("Timed out, states so far: $states")
    return states
  }

  /**
   * Collects periodic statuses until the worker is enqueued again after a run, that is an Enqueued
   * that carries a last result. An Enqueued without one comes before the first run if collection
   * starts before the run does.
   */
  private suspend fun Flow<PeriodicSyncJobStatus>.untilEnqueued(): List<PeriodicSyncJobStatus> {
    val states = mutableListOf<PeriodicSyncJobStatus>()
    withTimeoutOrNull(20_000) {
      transformWhile {
          emit(it)
          !(it.currentSyncJobStatus is CurrentSyncJobStatus.Enqueued &&
            it.lastSyncJobStatus != null)
        }
        .collect { states.add(it) }
    }
      ?: fail(
        "Timed out, states so far: ${states.map { it.currentSyncJobStatus to it.lastSyncJobStatus }}",
      )
    return states
  }
}
