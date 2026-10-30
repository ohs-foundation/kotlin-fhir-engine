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

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Asset directory where `stageBenchmarkAssets` puts the Synthea data; an app cannot read the host
 * filesystem or, from API 30, `/data/local/tmp`.
 */
private const val ASSET_DIRECTORY = "bulk_data"

private fun assets(): Context? = AndroidBenchmarkContext.context

internal actual suspend fun listDataFiles(): List<String> {
  val context = assets() ?: return emptyList()
  return try {
    context.assets.list(ASSET_DIRECTORY)?.toList().orEmpty().sorted()
  } catch (e: IOException) {
    emptyList()
  }
}

/**
 * Streamed, since a whole file can exceed the heap. A missing asset yields nothing; a read failure
 * mid-file throws.
 */
internal actual fun dataFileLines(relativePath: String): Flow<String> = flow {
  val context = assets() ?: return@flow
  val stream =
    try {
      context.assets.open("$ASSET_DIRECTORY/$relativePath")
    } catch (e: IOException) {
      return@flow
    }
  stream.bufferedReader().use { reader -> reader.lineSequence().forEach { emit(it) } }
}
