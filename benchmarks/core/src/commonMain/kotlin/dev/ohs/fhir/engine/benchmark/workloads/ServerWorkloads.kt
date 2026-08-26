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
package dev.ohs.fhir.engine.benchmark.workloads

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.benchmark.BenchmarkEnv
import dev.ohs.fhir.engine.benchmark.Isolation
import dev.ohs.fhir.engine.benchmark.Workload
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.engine.sync.AcceptRemoteConflictResolver
import dev.ohs.fhir.engine.sync.ConflictResolver
import dev.ohs.fhir.engine.sync.DownloadWorkManager
import dev.ohs.fhir.engine.sync.FhirSyncTask
import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.fhir.engine.sync.download.ResourceParamsBasedDownloadWorkManager
import dev.ohs.fhir.engine.sync.download.ResourceSearchParams
import dev.ohs.fhir.engine.sync.runSync
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType

/**
 * Sync against a real FHIR server, which is the only way upload can be measured at all: the patch
 * generation, patch ordering and bundle generation that `syncUpload` drives all live behind
 * `internal` types, and an external caller can only ever report failure.
 *
 * Skipped unless `-Pbenchmark.server` is set; see `benchmarks/tools/`. The numbers include the
 * server and the network, so they are only comparable to another run against the same server.
 */
object ServerWorkloads {

  const val GROUP = "server"

  fun all(): List<Workload> = listOf(UploadCreates, UploadUpdates, DownloadFromServer)

  /** Patient only: a full Synthea corpus would measure the server far more than the engine. */
  private const val UPLOAD_BATCH = 100

  /** Bundled rather than one request per change, which is what a real client would do. */
  private fun uploadStrategy() =
    UploadStrategy.forBundleRequest(
      methodForCreate = HttpCreateMethod.PUT,
      methodForUpdate = HttpUpdateMethod.PATCH,
      squash = true,
      bundleSize = 500,
    )

  /** New resources uploaded as a bundle: patch generation, bundling, POST, consolidation. */
  private object UploadCreates : Workload {
    override val id = "server.upload_creates"
    override val group = GROUP
    override val opsPerIteration = UPLOAD_BATCH

    // The upload has to start from a database whose only local changes are the ones just made,
    // otherwise the first iteration uploads a backlog and the rest upload nothing.
    override val isolation = Isolation.CLEAR_TABLES

    private var revision = 0

    override suspend fun beforeEach(env: BenchmarkEnv) {
      revision++
      repeat(UPLOAD_BATCH) { index ->
        env.engine.create(
          Patient(
            id = "bench-upload-$revision-$index",
            name = listOf(HumanName(family = FhirString("Upload$revision"))),
          ),
        )
      }
    }

    override suspend fun run(env: BenchmarkEnv) {
      env.syncTask().runSyncOrThrow()
    }
  }

  /** Updates rather than creates, so the patch generator diffs against a stored resource. */
  private object UploadUpdates : Workload {
    override val id = "server.upload_updates"
    override val group = GROUP
    override val opsPerIteration = UPLOAD_BATCH
    override val isolation = Isolation.CLEAR_TABLES

    private var revision = 0

    override suspend fun beforeEach(env: BenchmarkEnv) {
      revision++
      val ids = (0 until UPLOAD_BATCH).map { "bench-update-$it" }
      // Created and pushed first, so the measured sync carries updates and nothing else.
      ids.forEach { id -> env.engine.create(Patient(id = id)) }
      env.syncTask().runSyncOrThrow()
      ids.forEach { id ->
        val patient = env.engine.get<Patient>(id)
        env.engine.update(
          patient.copy(name = listOf(HumanName(family = FhirString("Revision$revision")))),
        )
      }
    }

    override suspend fun run(env: BenchmarkEnv) {
      env.syncTask().runSyncOrThrow()
    }
  }

  /** The download half against a real server: paging, parsing, conflict resolution, indexing. */
  private object DownloadFromServer : Workload {
    override val id = "server.download"
    override val group = GROUP
    override val opsPerIteration = 1
    override val isolation = Isolation.CLEAR_TABLES

    override suspend fun run(env: BenchmarkEnv) {
      env
        .syncTask(downloadParams = mapOf(ResourceType.Patient to mapOf("_count" to "100")))
        .runSyncOrThrow()
    }
  }

  private fun BenchmarkEnv.syncTask(downloadParams: ResourceSearchParams = emptyMap()) =
    BenchmarkSyncTask(engine, downloadParams)

  private suspend fun FhirSyncTask.runSyncOrThrow() {
    val status = runSync(taskName = null, onProgress = {})
    check(status is SyncJobStatus.Succeeded) { "Sync did not succeed: $status" }
  }

  private class BenchmarkSyncTask(
    private val engine: FhirEngine,
    private val downloadParams: ResourceSearchParams,
  ) : FhirSyncTask {

    override fun getFhirEngine(): FhirEngine = engine

    override fun getDownloadWorkManager(): DownloadWorkManager =
      ResourceParamsBasedDownloadWorkManager(downloadParams, NoTimestamps)

    override fun getConflictResolver(): ConflictResolver = AcceptRemoteConflictResolver

    override fun getUploadStrategy(): UploadStrategy = uploadStrategy()
  }

  /**
   * Every iteration starts from a cleared database, so remembering a high-water mark would make the
   * second iteration download nothing.
   */
  private object NoTimestamps : ResourceParamsBasedDownloadWorkManager.TimestampContext {
    override suspend fun saveLastUpdatedTimestamp(
      resourceType: ResourceType,
      timestamp: String?,
    ) = Unit

    override suspend fun getLasUpdateTimestamp(resourceType: ResourceType): String? = null
  }
}
