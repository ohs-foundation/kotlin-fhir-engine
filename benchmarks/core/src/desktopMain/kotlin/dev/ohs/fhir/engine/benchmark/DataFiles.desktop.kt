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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Set by the Gradle task that packages the data; see `benchmarks/core/build.gradle.kts`. */
private fun dataDirectory(): File? =
  System.getProperty("benchmark.data.dir")?.let(::File)?.takeIf { it.isDirectory }

internal actual suspend fun listDataFiles(): List<String> =
  dataDirectory()?.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

internal actual fun dataFileLines(relativePath: String): Flow<String> = flow {
  val file = dataDirectory()?.resolve(relativePath)?.takeIf { it.isFile } ?: return@flow
  file.bufferedReader().use { reader -> reader.lineSequence().forEach { emit(it) } }
}
