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

import kotlin.time.Clock

internal actual fun benchmarkPlatformContext(): Any = Unit

/**
 * A fixed OPFS filename prefix. A new prefix per call would not give a cold database, because the
 * first worker holds its exclusive handle for the page's lifetime.
 */
internal actual fun benchmarkStorageDirectory(): String? = "benchmark"

internal actual fun platformDescriptor() =
  PlatformDescriptor(
    target = webTargetName(),
    os = webUserAgent(),
    cpuCount = webHardwareConcurrency(),
  )

/**
 * False: closing terminates the SQLite worker, and a second open waits forever on the first
 * worker's exclusive OPFS handle. The runner falls back to [Isolation.CLEAR_TABLES].
 */
internal actual fun supportsFreshDatabase(): Boolean = false

internal actual fun nowIso8601(): String = Clock.System.now().toString()

internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) = Unit

/** POSTs to the test server (`karma.config.d/benchmark-report.js`), else prints to the console. */
internal actual suspend fun emitReport(fileName: String, json: String) {
  // Zero means every span was lost.
  println("Performance timeline: ${webTimelineMeasureCount()} measures recorded")
  if (httpPost("/benchmark-report/$fileName", json)) {
    println("Benchmark report written to $fileName")
  } else {
    println("BENCHMARK_REPORT $fileName")
    println(json)
  }
}

internal expect fun webTargetName(): String

/** `navigator.hardwareConcurrency`, or null where the browser withholds it. */
internal expect fun webHardwareConcurrency(): Int?

/** How many `performance.measure` entries the run produced; see `BenchmarkSpan.web.kt`. */
internal expect fun webTimelineMeasureCount(): Int

internal expect fun webUserAgent(): String

/** No storage persists between browser runs, so every run rescans. */
internal actual suspend fun readScratchFile(name: String): String? = null

internal actual suspend fun writeScratchFile(name: String, contents: String) = Unit
