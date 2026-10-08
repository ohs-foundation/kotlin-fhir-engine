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

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.db.impl.DatabaseConfig
import dev.ohs.fhir.engine.db.impl.DatabaseImpl
import dev.ohs.fhir.engine.impl.FhirEngineImpl
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.sync.download.DownloadState
import dev.ohs.fhir.engine.sync.download.Downloader
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import dev.ohs.fhir.engine.sync.upload.Uploader
import dev.ohs.fhir.engine.sync.upload.patch.PatchGeneratorFactory
import dev.ohs.fhir.engine.sync.upload.request.UploadRequestGeneratorFactory
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FhirSynchronizerTest {
  private val uploadStrategy =
    UploadStrategy.forBundleRequest(
      methodForCreate = HttpCreateMethod.PUT,
      methodForUpdate = HttpUpdateMethod.PATCH,
      squash = true,
      bundleSize = 500,
    )
  private val fhirDataStore =
    FhirDataStore(getDataStore(testPlatformContext(), testStorageDirectory()))

  @Test
  fun synchronize_downloadAndUploadSucceed_returnsSucceeded() =
    runTest(UnconfinedTestDispatcher()) {
      val synchronizer =
        synchronizer(engineWithOnePatient(), downloader(DownloadState.Success(listOf(), 10, 10)))
      val emitted = mutableListOf<SyncJobStatus>()
      // Undispatched so the collector is subscribed before synchronize emits its first state.
      backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        synchronizer.syncState.collect { emitted.add(it) }
      }

      val result = synchronizer.synchronize()

      assertIs<SyncJobStatus.Started>(emitted[0])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.DOWNLOAD, 10, 10), emitted[1])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 0), emitted[2])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 1), emitted[3])
      assertIs<SyncJobStatus.Succeeded>(emitted[4])
      assertIs<SyncJobStatus.Succeeded>(result)
    }

  @Test
  fun synchronize_downloadFails_returnsFailed() =
    runTest(UnconfinedTestDispatcher()) {
      val error = ResourceSyncException(ResourceType.Patient, "Download error")
      val synchronizer =
        synchronizer(engineWithOnePatient(), downloader(DownloadState.Failure(error)))
      val emitted = mutableListOf<SyncJobStatus>()
      // Undispatched so the collector is subscribed before synchronize emits its first state.
      backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        synchronizer.syncState.collect { emitted.add(it) }
      }

      val result = synchronizer.synchronize()

      assertIs<SyncJobStatus.Started>(emitted[0])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 0), emitted[1])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 1), emitted[2])
      assertEquals(SyncJobStatus.Failed(listOf(error)), emitted[3])
      assertEquals(listOf(error), assertIs<SyncJobStatus.Failed>(result).exceptions)
    }

  @Test
  fun synchronize_uploadFails_returnsFailed() =
    runTest(UnconfinedTestDispatcher()) {
      val synchronizer =
        synchronizer(
          engineWithOnePatient(),
          downloader(DownloadState.Success(listOf(), 10, 10)),
          uploader(BundleDataSource { throw IllegalStateException("Upload error") }),
        )
      val emitted = mutableListOf<SyncJobStatus>()
      // Undispatched so the collector is subscribed before synchronize emits its first state.
      backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        synchronizer.syncState.collect { emitted.add(it) }
      }

      val result = synchronizer.synchronize()

      assertIs<SyncJobStatus.Started>(emitted[0])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.DOWNLOAD, 10, 10), emitted[1])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 0), emitted[2])
      val failed = assertIs<SyncJobStatus.Failed>(emitted[3])
      assertEquals("Upload error", failed.exceptions.single().exceptionMessage)
      assertEquals(failed.exceptions, assertIs<SyncJobStatus.Failed>(result).exceptions)
    }

  @Test
  fun synchronize_calledConcurrently_runsInOrder() =
    runTest(UnconfinedTestDispatcher()) {
      val delayedSynchronizer =
        synchronizer(
          engineWithOnePatient(),
          object : Downloader {
            override suspend fun download(): Flow<DownloadState> {
              delay(10)
              return flowOf(DownloadState.Success(listOf(), 10, 10))
            }
          },
        )
      val synchronizer =
        synchronizer(engineWithOnePatient(), downloader(DownloadState.Success(listOf(), 0, 0)))
      val emitted = mutableListOf<SyncJobStatus>()

      val jobs =
        listOf(delayedSynchronizer, synchronizer).map {
          backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            it.syncState.collect { status -> emitted.add(status) }
          }
          backgroundScope.launch { it.synchronize() }
        }
      jobs.forEach { it.join() }

      assertEquals(10, emitted.size)
      assertIs<SyncJobStatus.Started>(emitted[0])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.DOWNLOAD, 10, 10), emitted[1])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 0), emitted[2])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 1), emitted[3])
      assertIs<SyncJobStatus.Succeeded>(emitted[4])
      assertIs<SyncJobStatus.Started>(emitted[5])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.DOWNLOAD, 0, 0), emitted[6])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 0), emitted[7])
      assertEquals(SyncJobStatus.InProgress(SyncOperation.UPLOAD, 1, 1), emitted[8])
      assertIs<SyncJobStatus.Succeeded>(emitted[9])
    }

  private fun synchronizer(
    fhirEngine: FhirEngine,
    downloader: Downloader,
    uploader: Uploader = uploader(acceptingDataSource()),
  ) =
    FhirSynchronizer(
      fhirEngine,
      UploadConfiguration(uploader, uploadStrategy),
      DownloadConfiguration(downloader, AcceptLocalConflictResolver),
      fhirDataStore,
    )

  private fun uploader(dataSource: DataSource) =
    Uploader(
      dataSource,
      PatchGeneratorFactory.byMode(uploadStrategy.patchGeneratorMode),
      UploadRequestGeneratorFactory.byMode(uploadStrategy.requestGeneratorMode),
    )

  /** Answers every bundle upload with a transaction response that echoes the patient back. */
  private fun acceptingDataSource() = BundleDataSource {
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction_Response),
      entry = listOf(Bundle.Entry(resource = TEST_PATIENT)),
    )
  }

  private fun downloader(state: DownloadState) =
    object : Downloader {
      override suspend fun download(): Flow<DownloadState> = flowOf(state)
    }

  private val databases = mutableListOf<DatabaseImpl>()

  @AfterTest
  fun tearDown() {
    databases.forEach { it.close() }
    databases.clear()
  }

  private suspend fun engineWithOnePatient(): FhirEngine {
    val database =
      DatabaseImpl(
        testPlatformContext(),
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        testStorageDirectory(),
        DatabaseConfig(inMemory = true),
      )
    databases.add(database)
    return FhirEngineImpl(database).apply { create(TEST_PATIENT) }
  }

  private companion object {
    val TEST_PATIENT = Patient(id = "test_patient_1")
  }
}
