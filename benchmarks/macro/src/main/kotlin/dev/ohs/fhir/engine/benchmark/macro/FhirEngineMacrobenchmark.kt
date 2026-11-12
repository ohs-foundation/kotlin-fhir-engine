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

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.ohs.fhir.engine.benchmark.workloads.Workloads
import java.util.regex.Pattern
import org.junit.Rule

/** Drives one workload from the `:benchmarks:core` catalogue through the driver app. */
abstract class FhirEngineMacrobenchmark {

  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  protected fun measure(workloadId: String) {
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      // One launch emits the section once. When it is missing, Sum reports 0 and does not fail.
      metrics = listOf(TraceSectionMetric(workloadId, mode = TraceSectionMetric.Mode.Sum)),
      // Fixed, so adding a baseline profile does not change the numbers.
      compilationMode = CompilationMode.Full(),
      iterations = ITERATIONS,
      // Kills the process, and the engine's in-memory state, between iterations.
      startupMode = StartupMode.COLD,
      setupBlock = {
        // A sleeping device renders no frames, so the launch cannot be confirmed.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
      },
    ) {
      startActivityAndWait(
        android.content.Intent().apply {
          setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.BenchmarkActivity")
          action = ACTION_RUN
          putExtra("workload", workloadId)
          putExtra("profile", PROFILE)
          putExtra("dataset", DATASET)
          putExtra("server", SERVER)
          putExtra("mix", MIX)
        },
      )

      // The measured section closes when the driver shows `done`. A failure also ends the wait.
      val settled =
        device.wait(
          Until.hasObject(
            By.res(TARGET_PACKAGE, STATUS_VIEW_ID).text(SETTLED_STATUS),
          ),
          TIMEOUT_MILLIS,
        )
      val actual =
        device.findObject(By.res(TARGET_PACKAGE, STATUS_VIEW_ID))?.text ?: "<no status view>"
      check(settled == true && actual == STATUS_DONE) {
        val visible =
          device.findObjects(By.pkg(TARGET_PACKAGE)).joinToString {
            "${it.className}[res=${it.resourceName}, text=${it.text}]"
          }
        val what =
          if (actual.startsWith(STATUS_FAILED)) {
            "failed"
          } else {
            "did not finish within ${TIMEOUT_MILLIS}ms"
          }
        "Workload $workloadId $what. " +
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
    const val STATUS_FAILED = "failed"

    /** Either terminal status. */
    val SETTLED_STATUS: Pattern = Pattern.compile("$STATUS_DONE|$STATUS_FAILED.*", Pattern.DOTALL)

    /** Overridable with `-Pandroid.testInstrumentationRunnerArguments.profile=…`. */
    val PROFILE: String =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("profile", "standard")

    /** `synthea` needs the app built with the data staged into its assets. */
    val DATASET: String =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("dataset", "synthetic")

    /** Base URL for the `server` group, from `-Pbenchmark.server`. Empty when unset. */
    val SERVER: String =
      androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("server", "")

    /** Clinical resources generated per patient, e.g. `8x2`. `off` loads the corpus alone. */
    val MIX: String =
      androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mix", "")

    val ITERATIONS: Int =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("iterations", "5")
        .toInt()

    val TIMEOUT_MILLIS: Long =
      androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("timeoutMillis", "600000")
        .toLong()

    /** Restricts the run to one workload id. The sweep script sets it for every run. */
    val ONLY: String? =
      androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("workload")

    /**
     * The group's workload ids. When [ONLY] is set, only that id, or none when it belongs to
     * another group.
     */
    fun workloadIds(group: String): List<String> =
      Workloads.byGroup(group).map { it.id }.filter { ONLY == null || it == ONLY }
  }
}
