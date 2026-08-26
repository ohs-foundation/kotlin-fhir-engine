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
package dev.ohs.fhir.engine.benchmark.macro

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.ohs.fhir.engine.benchmark.Isolation
import dev.ohs.fhir.engine.benchmark.Workload
import org.junit.Rule

/**
 * Shared driving logic. Subclasses only choose which workload group to run.
 *
 * Workloads come from `:benchmarks:core`, the same catalogue the in-process harnesses use, so a
 * query is never defined twice.
 */
abstract class FhirEngineMacrobenchmark {

  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  protected fun measure(workload: Workload) {
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      // Sum, not per-occurrence: one launch emits the section once, and Sum fails loudly at zero
      // rather than quietly reporting nothing.
      metrics = listOf(TraceSectionMetric(workload.id, mode = TraceSectionMetric.Mode.Sum)),
      iterations = ITERATIONS,
      // Kills the process between iterations, which also discards the engine's in-memory state.
      startupMode = StartupMode.COLD,
      setupBlock = {
        // The screen times out during a long workload, and a sleeping device renders no frames, so
        // the next iteration's launch cannot be confirmed.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        if (workload.isolation == Isolation.FRESH_DATABASE) {
          // Android ignores the engine's storageDirectory, so a cold database means clearing the
          // app's data outright.
          Runtime.getRuntime().exec(arrayOf("pm", "clear", TARGET_PACKAGE)).waitFor()
        }
      },
    ) {
      startActivityAndWait(
        android.content.Intent().apply {
          setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.BenchmarkActivity")
          action = ACTION_RUN
          putExtra("workload", workload.id)
          putExtra("profile", PROFILE)
          putExtra("dataset", DATASET)
        },
      )

      // Wait on the driver's own status rather than a sleep: the measured section has not closed
      // until the workload says it is done.
      val done =
        device.wait(
          Until.hasObject(By.res(TARGET_PACKAGE, STATUS_VIEW_ID).text(STATUS_DONE)),
          TIMEOUT_MILLIS,
        )
      check(done) {
        val actual =
          device.findObject(By.res(TARGET_PACKAGE, STATUS_VIEW_ID))?.text ?: "<no status view>"
        val visible =
          device.findObjects(By.pkg(TARGET_PACKAGE)).joinToString {
            "${it.className}[res=${it.resourceName}, text=${it.text}]"
          }
        "Workload ${workload.id} did not finish within ${TIMEOUT_MILLIS}ms. " +
          "Status: $actual. Current window: ${device.currentPackageName}. " +
          "Nodes for $TARGET_PACKAGE: ${visible.ifEmpty { "<none>" }}"
      }
    }
  }

  companion object {
    const val TARGET_PACKAGE = "dev.ohs.fhir.engine.benchmark.app"
    const val ACTION_RUN = "dev.ohs.fhir.engine.benchmark.RUN"
    const val STATUS_VIEW_ID = "benchmark_status"
    const val STATUS_DONE = "done"

    /** Overridable with `-Pandroid.testInstrumentationRunnerArguments.profile=…`. */
    val PROFILE: String =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("profile", "standard")

    /**
     * `synthea` only resolves if the app was built with the data staged into its assets; otherwise
     * the harness falls back to the synthetic dataset and says so in its report.
     */
    val DATASET: String =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("dataset", "synthetic")

    val ITERATIONS: Int =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("iterations", "5")
        .toInt()

    /** Overridable so a broken selector surfaces in seconds instead of ten minutes. */
    val TIMEOUT_MILLIS: Long =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("timeoutMillis", "600000")
        .toLong()

    /** Restricts a class to one workload id, for debugging a single measurement. */
    val ONLY: String? =
      androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("workload")
  }
}
