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
import dev.ohs.fhir.engine.benchmark.data.Dataset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Runs workloads in-process, for desktop, iOS and web. Android uses macrobenchmark instead, which
 * owns iteration control but executes the same [Workload] objects.
 */
class BenchmarkRunner(
  private val config: BenchmarkConfig,
  private val dataset: Dataset,
  private var engine: FhirEngine,
  private val platformContext: Any,
  private val reopenEngine: suspend () -> FhirEngine,
  private val serverUrl: String? = null,
  /** Fires between iterations only, never inside [benchmarkSpan]. */
  private val onProgress: ProgressListener = {},
) {

  suspend fun run(workloads: List<Workload>): BenchmarkReport {
    onProgress(BenchmarkProgress.RunStarted(workloads.size))
    val results =
      workloads.mapIndexed { index, workload ->
        // `also` rather than an emission inside runWorkload, so the failure path reports too.
        runWorkload(workload, index, workloads.size).also {
          onProgress(BenchmarkProgress.WorkloadFinished(it, index, workloads.size))
        }
      }
    return BenchmarkReport(
      timestamp = nowIso8601(),
      platform = platformDescriptor(),
      config = config,
      dataset = dataset.manifest(),
      results = results,
    )
  }

  private suspend fun runWorkload(workload: Workload, index: Int, total: Int): WorkloadResult {
    val (isolation, note) = resolveIsolation(workload.isolation)
    val samples = mutableListOf<Double>()
    return try {
      onProgress(BenchmarkProgress.Preparing(workload.id, workload.group, index, total))
      var env = newEnv()
      workload.prepare(env)

      repeat(config.warmupIterations + config.measuredIterations) { iteration ->
        // Before applyIsolation, which reopens the database and is itself slow.
        onProgress(
          BenchmarkProgress.Iterating(
            workloadId = workload.id,
            group = workload.group,
            index = index,
            total = total,
            iteration = iteration,
            warmupIterations = config.warmupIterations,
            measuredIterations = config.measuredIterations,
          ),
        )
        env = applyIsolation(isolation, env)
        workload.beforeEach(env)

        val start = TimeSource.Monotonic.markNow()
        benchmarkSpan(workload.id) { workload.run(env) }
        val elapsed = start.elapsedNow()

        workload.afterEach(env)
        if (iteration >= config.warmupIterations) {
          samples += elapsed.inWholeMicroseconds / 1000.0
        }
      }

      val statistics = Statistics.of(samples)
      WorkloadResult(
        id = workload.id,
        group = workload.group,
        opsPerIteration = workload.opsPerIteration,
        isolationApplied = isolation.name.lowercase(),
        isolationNote = note,
        samplesMillis = samples,
        statistics = statistics,
        medianMillisPerOp = statistics.median / workload.opsPerIteration,
      )
    } catch (e: CancellationException) {
      // Never a workload failure. Swallowing it would let the rest of the catalogue blow through in
      // milliseconds, reporting fake errors to the progress listener and writing a bogus report.
      throw e
    } catch (e: Throwable) {
      // Record the failure rather than losing the whole run to one broken workload.
      WorkloadResult(
        id = workload.id,
        group = workload.group,
        opsPerIteration = workload.opsPerIteration,
        isolationApplied = isolation.name.lowercase(),
        isolationNote = note,
        samplesMillis = samples,
        statistics = if (samples.isEmpty()) EMPTY_STATISTICS else Statistics.of(samples),
        medianMillisPerOp = 0.0,
        error = "${e::class.simpleName}: ${e.message}",
      )
    }
  }

  /** Degrades [requested] where the platform cannot honour it, and says so. */
  private fun resolveIsolation(requested: Isolation): Pair<Isolation, String?> =
    if (requested == Isolation.FRESH_DATABASE && !supportsFreshDatabase()) {
      Isolation.CLEAR_TABLES to
        "Requested fresh_database but this platform cannot reopen the database in-process, so the " +
          "database was cleared instead and the page cache stayed warm. Treat as a lower bound."
    } else {
      requested to null
    }

  private suspend fun applyIsolation(isolation: Isolation, env: BenchmarkEnv): BenchmarkEnv =
    when (isolation) {
      Isolation.NONE -> env
      Isolation.CLEAR_TABLES -> env.also { it.engine.clearDatabase() }
      Isolation.FRESH_DATABASE -> {
        engine = reopenEngine()
        newEnv()
      }
    }

  private fun newEnv() =
    BenchmarkEnv(
      engine = engine,
      dataset = dataset,
      platformContext = platformContext,
      reopenEngine = reopenEngine,
      serverUrl = serverUrl,
    )

  private companion object {
    val EMPTY_STATISTICS = Statistics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
  }
}
