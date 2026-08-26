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

import java.io.File
import kotlin.time.Clock

internal actual fun benchmarkPlatformContext(): Any = Unit

/**
 * A fresh directory on every call, so reopening the engine yields a genuinely cold database file
 * rather than a cleared one. Rooted at `-Dbenchmark.storage.dir` when set.
 */
internal actual fun benchmarkStorageDirectory(): String? {
  val root = System.getProperty("benchmark.storage.dir")?.let(::File) ?: File("build/benchmark-db")
  val directory = File(root, "run-${databaseCounter++}-${System.nanoTime()}")
  directory.mkdirs()
  return directory.absolutePath
}

private var databaseCounter = 0

internal actual fun platformDescriptor() =
  PlatformDescriptor(
    target = "desktop",
    os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
    deviceModel = System.getProperty("os.arch"),
    cpuCount = Runtime.getRuntime().availableProcessors(),
  )

internal actual fun supportsFreshDatabase(): Boolean = true

internal actual fun nowIso8601(): String = Clock.System.now().toString()

/** No-op: [benchmarkStorageDirectory] already hands out an unused directory per call. */
internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) = Unit

internal actual suspend fun emitReport(fileName: String, json: String) {
  val directory = reportDirectory()
  directory.mkdirs()
  val file = File(directory, fileName)
  file.writeText(json)
  println("Benchmark report written to ${file.absolutePath}")
}

/** `-Dbenchmark.report.dir` if set, else the module's build output. */
private fun reportDirectory(): File =
  File(System.getProperty("benchmark.report.dir") ?: "build/reports/benchmarks")
