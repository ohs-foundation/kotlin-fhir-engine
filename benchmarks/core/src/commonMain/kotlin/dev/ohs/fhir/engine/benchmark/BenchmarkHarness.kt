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
package dev.ohs.fhir.engine.benchmark

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.ServerConfiguration
import dev.ohs.fhir.engine.benchmark.data.Dataset
import dev.ohs.fhir.engine.benchmark.data.NdjsonDataset
import dev.ohs.fhir.engine.benchmark.data.SyntheticDataset
import dev.ohs.fhir.engine.benchmark.workloads.ServerWorkloads
import dev.ohs.fhir.engine.benchmark.workloads.Workloads

/** Wires engine, dataset and catalogue together, so every harness sets up identically. */
object BenchmarkHarness {

  suspend fun run(
    config: BenchmarkConfig = BenchmarkConfig.of(),
    onProgress: ProgressListener = {},
  ): BenchmarkReport {
    onProgress(BenchmarkProgress.LoadingDataset(config.datasetKind))
    return run(config, loadDataset(config), onProgress)
  }

  suspend fun run(
    config: BenchmarkConfig,
    dataset: Dataset,
    onProgress: ProgressListener = {},
  ): BenchmarkReport {
    onProgress(BenchmarkProgress.DatasetReady(dataset.manifest()))
    val platformContext = benchmarkPlatformContext()
    val engine = openEngine(platformContext, config.serverUrl)

    val runner =
      BenchmarkRunner(
        config = config,
        dataset = dataset,
        engine = engine,
        platformContext = platformContext,
        reopenEngine = { openEngine(platformContext, config.serverUrl) },
        serverUrl = config.serverUrl,
        onProgress = onProgress,
      )

    val report = runner.run(selectWorkloads(config))
    emitReport(reportFileName(report), report.toJson())
    onProgress(BenchmarkProgress.RunFinished(report))
    return report
  }

  /**
   * Prepares one workload for iteration-at-a-time measurement. Android needs this because
   * macrobenchmark owns iteration control and setup must happen with its timer stopped.
   */
  suspend fun setUpSingle(
    workloadId: String,
    config: BenchmarkConfig = BenchmarkConfig.of(),
    dataset: Dataset? = null,
  ): SingleRun {
    val workload = Workloads.byId(workloadId)
    val resolved = dataset ?: loadDataset(config)
    val platformContext = benchmarkPlatformContext()
    val engine = openEngine(platformContext, config.serverUrl)
    val env =
      BenchmarkEnv(
        engine = engine,
        dataset = resolved,
        platformContext = platformContext,
        reopenEngine = { openEngine(platformContext, config.serverUrl) },
        serverUrl = config.serverUrl,
      )
    workload.prepare(env)
    return SingleRun(workload, env)
  }

  /** A prepared workload. [measureOnce] is the only part that should be timed. */
  class SingleRun internal constructor(val workload: Workload, private val env: BenchmarkEnv) {

    /**
     * What actually loaded, not what was asked for. Synthea falls back to synthetic whenever the
     * data is missing, and the macrobenchmark path writes no report to record the difference.
     */
    val datasetManifest: DatasetManifest
      get() = env.dataset.manifest()

    /** Untimed per-iteration setup. Call with the harness timer stopped. */
    suspend fun beforeEach() = workload.beforeEach(env)

    /** The measured work, wrapped in the trace span named after the workload. */
    suspend fun measureOnce() = benchmarkSpan(workload.id) { workload.run(env) }

    /** Untimed per-iteration teardown. */
    suspend fun afterEach() = workload.afterEach(env)
  }

  /**
   * The dataset the config asks for, falling back to synthetic when Synthea data has not been
   * packaged for this platform. The report records which kind actually ran.
   */
  suspend fun loadDataset(config: BenchmarkConfig): Dataset {
    if (config.datasetKind != DatasetKind.SYNTHEA.name.lowercase()) return syntheticDataset(config)
    // Filter before reading: Synthea's Claim and ExplanationOfBenefit files dwarf everything the
    // workloads touch, and loading them just to discard them exhausts the heap.
    val names =
      listDataFiles().filter {
        it.endsWith(".ndjson") && it.substringBefore(".") in NdjsonDataset.INCLUDED_TYPES
      }
    if (names.isEmpty()) return syntheticDataset(config)
    val files = names.mapNotNull { name -> readDataFile(name)?.let { name to it } }.toMap()
    val dataset =
      NdjsonDataset(
        files = files,
        seed = config.seed,
        requestedPopulation = Profile.fromString(config.profile).population,
      )
    if (dataset.parseFailures.isNotEmpty()) {
      println(
        "Synthea parse failures: ${dataset.parseFailures.size}. First few:\n" +
          dataset.parseFailures.take(5).joinToString("\n"),
      )
    }
    return if (dataset.patientIds.isEmpty()) syntheticDataset(config) else dataset
  }

  private fun syntheticDataset(config: BenchmarkConfig): Dataset =
    SyntheticDataset(
      population = Profile.fromString(config.profile).population,
      seed = config.seed,
    )

  /**
   * The `server` group needs a server and every other group is slowed down by one, so it is only
   * run when `-Pbenchmark.server` names one. Silently returning nothing would look like a pass.
   */
  private fun selectWorkloads(config: BenchmarkConfig): List<Workload> {
    val selected = Workloads.byGroups(config.groups)
    if (config.serverUrl != null) return selected
    val (needsServer, rest) = selected.partition { it.group == ServerWorkloads.GROUP }
    if (needsServer.isNotEmpty()) {
      println(
        "Skipping ${needsServer.size} ${ServerWorkloads.GROUP} workloads: no -Pbenchmark.server.",
      )
    }
    return rest
  }

  /** A fresh storage directory per call, so [Isolation.FRESH_DATABASE] gets a cold file. */
  private suspend fun openEngine(platformContext: Any, serverUrl: String?): FhirEngine {
    if (FhirEngineProvider.isInitialized()) FhirEngineProvider.reset()
    deleteBenchmarkDatabase(platformContext)
    FhirEngineProvider.init(
      FhirEngineConfiguration(
        storageDirectory = benchmarkStorageDirectory(),
        serverConfiguration = serverUrl?.let { ServerConfiguration(baseUrl = it) },
      ),
      platformContext,
    )
    return FhirEngineProvider.getInstance(platformContext)
  }

  private fun reportFileName(report: BenchmarkReport): String =
    "${report.platform.target}-${report.timestamp.replace(":", "-")}.json"
}
