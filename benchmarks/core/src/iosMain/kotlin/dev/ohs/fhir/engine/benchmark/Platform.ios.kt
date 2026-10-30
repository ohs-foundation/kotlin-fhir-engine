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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIDevice
import platform.posix.getenv

internal actual fun benchmarkPlatformContext(): Any = Unit

/** A fresh directory per call, so reopening the engine gives a cold database. */
internal actual fun benchmarkStorageDirectory(): String? =
  "${NSTemporaryDirectory()}fhir-benchmark-${databaseCounter++}"

private var databaseCounter = 0

internal actual fun platformDescriptor() =
  PlatformDescriptor(
    target = "ios",
    os = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
    deviceModel = UIDevice.currentDevice.model,
  )

internal actual fun supportsFreshDatabase(): Boolean = true

internal actual fun nowIso8601(): String = Clock.System.now().toString()

internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) = Unit

/**
 * Writes to `BENCHMARK_REPORT_DIR` on the shared host filesystem, else the temporary directory;
 * prints to the console if the write fails.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun emitReport(fileName: String, json: String) {
  val directory =
    getenv("BENCHMARK_REPORT_DIR")?.toKString()?.takeIf { it.isNotBlank() }
      ?: NSTemporaryDirectory()
  NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)

  val path = "${directory.removeSuffix("/")}/$fileName"
  val data = (json as NSString).dataUsingEncoding(NSUTF8StringEncoding)
  if (data != null && NSFileManager.defaultManager.createFileAtPath(path, data, null)) {
    println("Benchmark report written to $path")
  } else {
    println("BENCHMARK_REPORT $fileName")
    println(json)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun scratchDirectory(): String? {
  val base =
    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull()
      as? String
      ?: return null
  val directory = "$base/benchmark-scratch"
  NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
  return directory
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readScratchFile(name: String): String? =
  scratchDirectory()?.let {
    NSString.stringWithContentsOfFile("$it/$name", NSUTF8StringEncoding, null)
  }

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun writeScratchFile(name: String, contents: String) {
  val directory = scratchDirectory() ?: return
  (contents as NSString).writeToFile("$directory/$name", true, NSUTF8StringEncoding, null)
}
