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

import kotlinx.coroutines.runBlocking

/** Desktop entry point: `--workload=…`, `--profile=…`, `--groups=…`. Same driver as Android. */
fun main(args: Array<String>) {
  val values =
    args
      .filter { it.startsWith("--") }
      .mapNotNull { argument ->
        val parts = argument.removePrefix("--").split("=", limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
      }
      .toMap()

  val request = BenchmarkRequest.from(values)
  runBlocking {
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
    println(summary)
  }
}
