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

/**
 * Read from the driver app's assets, which is where `stageBenchmarkAssets` puts the packaged
 * Synthea data. Android cannot read the host filesystem and `/data/local/tmp` is unreadable to an
 * app from API 30, so the corpus has to travel inside the APK.
 *
 * Empty when the app was built without the data, which leaves the harness on the synthetic dataset.
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

internal actual suspend fun readDataFile(relativePath: String): String? {
  val context = assets() ?: return null
  return try {
    context.assets.open("$ASSET_DIRECTORY/$relativePath").use { it.readBytes().decodeToString() }
  } catch (e: IOException) {
    null
  }
}
