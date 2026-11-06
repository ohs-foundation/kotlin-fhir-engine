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
 * A test because every Kotlin target has a test runner; it asserts only that the workloads ran.
 * Android runs the same workloads through macrobenchmark instead.
 *
 * Setup is inside `runTest`, not `@BeforeTest`: on Kotlin/Wasm the framework does not await the
 * `runTest` Promise for `@BeforeTest`.
 */
class FhirEngineBenchmarks {

  @Test
  fun runBenchmarks() =
    // Runs above `smoke` exceed the default 60 s timeout.
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

  /** Formats the report as a table for stdout. */
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
          "${formatOrAbsent(result.statistics.p90).padStart(10)} " +
          "${format(result.medianMillisPerOp).padStart(10)}  ${result.opsPerIteration}" +
          (result.error?.let { "  ERROR $it" } ?: "") +
          (result.isolationNote?.let { "  (isolation degraded)" } ?: ""),
      )
    }
  }

  /** `-` when p90 is absent (fewer than `Statistics.MIN_SAMPLES_FOR_P90` samples). */
  private fun formatOrAbsent(value: Double?): String = value?.let(::format) ?: "-"

  private fun format(value: Double): String {
    val scaled = (value * 1000).toLong()
    return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
  }
}
