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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv

/**
 * Read straight off the host filesystem: a simulator shares it, so the packaged Synthea directory
 * needs no bundling. Set by the Gradle test task; a device would need the data in its own bundle.
 */
@OptIn(ExperimentalForeignApi::class)
private fun dataDirectory(): String? =
  getenv("BENCHMARK_DATA_DIR")?.toKString()?.takeIf { it.isNotBlank() }

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun listDataFiles(): List<String> {
  val directory = dataDirectory() ?: return emptyList()

  @Suppress("UNCHECKED_CAST")
  val names =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) as? List<String>
  return names.orEmpty().sorted()
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readDataFile(relativePath: String): String? {
  val directory = dataDirectory() ?: return null
  return NSString.stringWithContentsOfFile(
    path = "$directory/$relativePath",
    encoding = NSUTF8StringEncoding,
    error = null,
  )
}
