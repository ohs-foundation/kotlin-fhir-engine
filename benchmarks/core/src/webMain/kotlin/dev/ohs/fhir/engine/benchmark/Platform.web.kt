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
 * Fixed, unlike the other platforms.
 *
 * The browser has no directories: the engine applies this as a filename prefix on the OPFS
 * database. Handing out a new prefix per call would not give a cold database anyway, because the
 * first worker keeps its exclusive handle for the lifetime of the page.
 */
internal actual fun benchmarkStorageDirectory(): String? = "benchmark"

internal actual fun platformDescriptor() =
  PlatformDescriptor(
    target = webTargetName(),
    os = webUserAgent(),
    cpuCount = webHardwareConcurrency(),
  )

/**
 * False: a browser page cannot close and reopen this database.
 *
 * Closing terminates the SQLite Web Worker, after which every call hangs rather than failing; not
 * closing leaves that worker holding the exclusive OPFS sync access handle, so a second open never
 * completes. The runner degrades [Isolation.FRESH_DATABASE] to [Isolation.CLEAR_TABLES] and records
 * that in the report. A genuinely cold web measurement needs a page reload, which is why the driver
 * app rather than this harness owns web isolation.
 */
internal actual fun supportsFreshDatabase(): Boolean = false

internal actual fun nowIso8601(): String = Clock.System.now().toString()

internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) = Unit

/**
 * POSTed to the test server, which writes it next to the other platforms' reports; see
 * `karma.config.d/benchmark-report.js`.
 *
 * A page served by webpack rather than Karma has no sink, so the console stays as the fallback.
 */
internal actual suspend fun emitReport(fileName: String, json: String) {
  // A zero here means every span was lost, the same silent failure that makes an all-zero
  // macrobenchmark look like a passing run.
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
