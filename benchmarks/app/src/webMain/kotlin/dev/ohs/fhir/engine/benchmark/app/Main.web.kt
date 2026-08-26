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

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Browser entry point: `?workload=…` or `?groups=search&profile=smoke`. Reloading the page is the
 * only cold-engine isolation available: the SQLite Web Worker cannot be reopened within one page.
 */
fun main() {
  val values =
    window.location.search
      .removePrefix("?")
      .split("&")
      .mapNotNull { pair ->
        val parts = pair.split("=", limit = 2)
        if (parts.size == 2) parts[0] to decodeURIComponent(parts[1]) else null
      }
      .toMap()

  val request = BenchmarkRequest.from(values)
  setStatus("running")

  CoroutineScope(Dispatchers.Main).launch {
    try {
      val summary =
        if (request.workloadId != null) {
          val run = BenchmarkDriver.prepareSingle(request)
          run.beforeEach()
          run.measureOnce()
          run.afterEach()
          "ran ${request.workloadId} once"
        } else {
          BenchmarkDriver.summarise(BenchmarkDriver.runAll(request))
        }
      setStatus("done: $summary")
    } catch (e: Throwable) {
      setStatus("failed: ${e::class.simpleName}: ${e.message}")
    }
  }
}

/** Written into the document so a browser-driving harness can read completion off the page. */
private fun setStatus(status: String) {
  document.body?.setAttribute("data-benchmark-status", status)
  println("BENCHMARK_STATUS $status")
}

private fun decodeURIComponent(value: String): String = value.replace("+", " ")
