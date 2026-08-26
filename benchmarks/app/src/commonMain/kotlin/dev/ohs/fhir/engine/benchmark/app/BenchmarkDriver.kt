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
package dev.ohs.fhir.engine.benchmark.app

import dev.ohs.fhir.engine.benchmark.BenchmarkHarness
import dev.ohs.fhir.engine.benchmark.BenchmarkReport
import dev.ohs.fhir.engine.benchmark.ProgressListener

/**
 * Two modes: a full run drives the catalogue and writes a report (desktop, web); a single-workload
 * run prepares state and exposes one measured call, which is what macrobenchmark needs.
 */
object BenchmarkDriver {

  /**
   * Runs whole groups and emits a report. Returns the report rather than a summary line: a caller
   * with a screen needs the results, and a caller with a console can call [summarise].
   */
  suspend fun runAll(
    request: BenchmarkRequest,
    onProgress: ProgressListener = {},
  ): BenchmarkReport = BenchmarkHarness.run(request.toConfig(), onProgress)

  /** One line for a console or a status view. */
  fun summarise(report: BenchmarkReport): String {
    val failed = report.results.count { it.error != null }
    return "ran ${report.results.size} workloads on ${report.platform.target}, $failed failed"
  }

  /** Prepares one workload. The caller decides when to measure. */
  suspend fun prepareSingle(request: BenchmarkRequest): BenchmarkHarness.SingleRun {
    val workloadId = requireNotNull(request.workloadId) { "prepareSingle needs a workload id." }
    return BenchmarkHarness.setUpSingle(workloadId, request.toConfig())
  }
}
