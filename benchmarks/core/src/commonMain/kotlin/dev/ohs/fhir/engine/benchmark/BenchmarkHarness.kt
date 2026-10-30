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
import dev.ohs.fhir.engine.benchmark.data.AugmentedDataset
import dev.ohs.fhir.engine.benchmark.data.ClinicalMix
import dev.ohs.fhir.engine.benchmark.data.Dataset
import dev.ohs.fhir.engine.benchmark.data.DatasetCache
import dev.ohs.fhir.engine.benchmark.data.NdjsonDataset
import dev.ohs.fhir.engine.benchmark.data.SyntheticDataset
import dev.ohs.fhir.engine.benchmark.workloads.ServerWorkloads
import dev.ohs.fhir.engine.benchmark.workloads.Workloads
import kotlinx.coroutines.flow.toList

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
        reopenEngineKeepingData = {
          openEngine(
            platformContext,
            config.serverUrl,
            resetDatabase = false,
            reuseStorage = true,
          )
        },
        serverUrl = config.serverUrl,
        onProgress = onProgress,
      )

    val report = runner.run(selectWorkloads(config))
    emitReport(reportFileName(report), report.toJson())
    onProgress(BenchmarkProgress.RunFinished(report))
    return report
  }

  /** Prepares one workload for externally driven iterations, as macrobenchmark requires. */
  suspend fun setUpSingle(
    workloadId: String,
    config: BenchmarkConfig = BenchmarkConfig.of(),
    dataset: Dataset? = null,
  ): SingleRun {
    val workload = Workloads.byId(workloadId)
    val resolved = dataset ?: loadDataset(config)
    val platformContext = benchmarkPlatformContext()
    // Read-only workloads keep the database across processes so the corpus is seeded once.
    val engine =
      openEngine(
        platformContext,
        config.serverUrl,
        resetDatabase = workload.isolation != Isolation.NONE,
      )
    val env =
      BenchmarkEnv(
        engine = engine,
        dataset = resolved,
        platformContext = platformContext,
        reopenEngine = { openEngine(platformContext, config.serverUrl) },
        reopenEngineKeepingData = {
          openEngine(
            platformContext,
            config.serverUrl,
            resetDatabase = false,
            reuseStorage = true,
          )
        },
        serverUrl = config.serverUrl,
      )
    workload.prepare(env)
    return SingleRun(workload, env)
  }

  /** A prepared workload. [measureOnce] is the only part that should be timed. */
  class SingleRun internal constructor(val workload: Workload, private val env: BenchmarkEnv) {

    /** The loaded dataset; the macrobenchmark path writes no report, so log this. */
    val datasetManifest: DatasetManifest
      get() = env.dataset.manifest()

    /** Untimed per-iteration setup. Call with the harness timer stopped. */
    suspend fun beforeEach() = workload.beforeEach(env)

    /** The measured work, wrapped in the trace span named after the workload. */
    suspend fun measureOnce() = benchmarkSpan(workload.id) { workload.run(env) }

    /** Untimed per-iteration teardown. */
    suspend fun afterEach() = workload.afterEach(env)
  }

  /** The dataset the config asks for. Fails rather than substituting synthetic data for Synthea. */
  suspend fun loadDataset(config: BenchmarkConfig): Dataset {
    if (config.datasetKind != DatasetKind.SYNTHEA.name.lowercase()) return syntheticDataset(config)
    // Filter before reading: unused Synthea types are large enough to exhaust the heap.
    val names =
      listDataFiles().filter {
        it.endsWith(".ndjson") && it.substringBefore(".") in NdjsonDataset.INCLUDED_TYPES
      }
    if (names.isEmpty()) {
      missingSyntheaData("no .ndjson file for any of the types the workloads query")
    }
    val corpus = corpusKey()
    val dataset =
      NdjsonDataset.load(
        fileNames = names,
        seed = config.seed,
        requestedPopulation = config.effectivePopulation,
        lines = ::dataFileLines,
        cache = ScratchFileCache,
        // Bump the version when the cached scan's shape changes.
        cacheKey = "dataset-v2-$corpus.json",
      )
    if (dataset.parseFailures.isNotEmpty()) {
      println(
        "Synthea parse failures: ${dataset.parseFailures.size}. First few:\n" +
          dataset.parseFailures.take(5).joinToString("\n"),
      )
    }
    if (dataset.patientIds.isEmpty()) {
      missingSyntheaData("${names.size} file(s) read, but none of them yielded a Patient")
    }
    // Observations and conditions are generated on top of the corpus; `off` skips them.
    val mix = ClinicalMix.parse(config.clinicalMix)
    if (mix.perPatient == 0) return dataset
    return AugmentedDataset(dataset, mix, seed = config.seed)
  }

  /** A hash of the packaged corpus manifest, which changes whenever the corpus does. */
  private suspend fun corpusKey(): String {
    val manifest = dataFileLines("manifest.json").toList().joinToString("\n")
    if (manifest.isBlank()) return "unknown"
    var hash = 17L
    for (character in manifest) hash = hash * 31 + character.code
    return hash.toULong().toString(16).padStart(16, '0')
  }

  /** Backed by [readScratchFile] and [writeScratchFile]. */
  private object ScratchFileCache : DatasetCache {
    override suspend fun read(key: String): String? = readScratchFile(key)

    override suspend fun write(key: String, contents: String) = writeScratchFile(key, contents)
  }

  /** Ends a run that asked for Synthea and cannot have it. */
  private fun missingSyntheaData(detail: String): Nothing =
    error(
      "benchmark.dataset=synthea, but the Synthea data is not readable here: $detail. " +
        "Package it with `./gradlew :benchmarks:core:packageBenchmarkData`. On Android the " +
        "corpus travels inside the APK, so also reinstall the driver app with " +
        "-Pbenchmark.dataset=synthea; on web it is served by Karma, so rerun the Gradle task " +
        "rather than an already-running browser.",
    )

  private fun syntheticDataset(config: BenchmarkConfig): Dataset =
    SyntheticDataset(
      population = config.effectivePopulation,
      seed = config.seed,
    )

  /** [BenchmarkConfig.workloadIds] win over groups; `server` workloads need a server URL. */
  private fun selectWorkloads(config: BenchmarkConfig): List<Workload> {
    val selected =
      if (config.workloadIds.isNotEmpty()) {
        config.workloadIds.map { Workloads.byId(it) }
      } else {
        Workloads.byGroups(config.groups)
      }
    if (config.serverUrl != null) return selected
    val (needsServer, rest) = selected.partition { it.group == ServerWorkloads.GROUP }
    if (needsServer.isNotEmpty()) {
      println(
        "Skipping ${needsServer.size} ${ServerWorkloads.GROUP} workloads: no -Pbenchmark.server.",
      )
    }
    return rest
  }

  /** Where the engine currently open was told to keep its database. */
  private var storageDirectory: String? = null

  /**
   * A fresh storage directory per call, so [Isolation.FRESH_DATABASE] gets a cold file, unless
   * [reuseStorage] reopens over the current one, as [Isolation.COLD_CACHE] requires.
   */
  private suspend fun openEngine(
    platformContext: Any,
    serverUrl: String?,
    resetDatabase: Boolean = true,
    reuseStorage: Boolean = false,
  ): FhirEngine {
    if (FhirEngineProvider.isInitialized()) FhirEngineProvider.resetForTesting()
    if (resetDatabase) deleteBenchmarkDatabase(platformContext)
    val directory =
      if (reuseStorage) {
        storageDirectory ?: benchmarkStorageDirectory()
      } else {
        benchmarkStorageDirectory()
      }
    storageDirectory = directory
    FhirEngineProvider.init(
      FhirEngineConfiguration(
        storageDirectory = directory,
        serverConfiguration = serverUrl?.let { ServerConfiguration(baseUrl = it) },
      ),
      platformContext,
    )
    return FhirEngineProvider.getInstance(platformContext)
  }

  private fun reportFileName(report: BenchmarkReport): String =
    "${report.platform.target}-${report.timestamp.replace(":", "-")}.json"
}
