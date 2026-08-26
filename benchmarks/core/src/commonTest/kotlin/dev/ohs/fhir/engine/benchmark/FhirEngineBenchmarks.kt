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

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * In-process benchmark entry point for desktop, iOS and web.
 *
 * Shaped as a test only because that is the runner every Kotlin target already has; nothing here
 * asserts correctness beyond "the workloads ran". Android does not come through here — it goes via
 * macrobenchmark against the driver app, executing the same
 * [dev.ohs.fhir.engine.benchmark.Workload] objects.
 *
 * Setup lives inside the `runTest` body rather than `@BeforeTest` because on Kotlin/Wasm `runTest`
 * returns a Promise the framework does not await for `@BeforeTest`.
 */
class FhirEngineBenchmarks {

  @Test
  fun runBenchmarks() =
    // The default 60s timeout kills any real run. Populations above `smoke` take minutes.
    runTest(timeout = 60.minutes) {
      val config = benchmarkConfigFromEnvironment()
      val report = BenchmarkHarness.run(config)

      println(summarise(report))

      val failures = report.results.filter { it.error != null }
      assertTrue(
        failures.isEmpty(),
        "Workloads failed:\n" + failures.joinToString("\n") { "  ${it.id}: ${it.error}" },
      )
    }

  /**
   * A compact table on stdout, so a run is readable without opening the JSON.
   *
   * Read the spread across the search rows first. If those medians are all but identical, the page
   * cache was never disturbed and the search numbers mean nothing.
   */
  private fun summarise(report: BenchmarkReport): String = buildString {
    appendLine()
    appendLine("Benchmark report - ${report.platform.target} (${report.platform.os})")
    appendLine(
      "  dataset ${report.dataset.kind} population=${report.dataset.population} " +
        "fingerprint=${report.dataset.fingerprint}",
    )
    appendLine(
      "  config  profile=${report.config.profile} warmup=${report.config.warmupIterations} " +
        "measured=${report.config.measuredIterations}",
    )
    appendLine()
    appendLine(
      "  ${"workload".padEnd(42)} ${"median ms".padStart(11)} ${"p90 ms".padStart(10)} " +
        "${"ms/op".padStart(10)}  ops",
    )
    appendLine("  ${"-".repeat(42)} ${"-".repeat(11)} ${"-".repeat(10)} ${"-".repeat(10)}  ----")
    for (result in report.results) {
      appendLine(
        "  ${result.id.padEnd(42)} " +
          "${format(result.statistics.median).padStart(11)} " +
          "${format(result.statistics.p90).padStart(10)} " +
          "${format(result.medianMillisPerOp).padStart(10)}  ${result.opsPerIteration}" +
          (result.error?.let { "  ERROR $it" } ?: "") +
          (result.isolationNote?.let { "  (isolation degraded)" } ?: ""),
      )
    }
  }

  private fun format(value: Double): String {
    val scaled = (value * 1000).toLong()
    return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
  }
}
