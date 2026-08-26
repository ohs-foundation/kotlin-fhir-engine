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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Served by the middleware in `karma.config.d/benchmark-data.js`. */
private const val DATA_ROOT = "/benchmark-data"

/**
 * Derived from the manifest rather than a directory listing, because HTTP has none.
 *
 * Empty when the dataset was never packaged, which sends the harness to the synthetic fallback.
 */
internal actual suspend fun listDataFiles(): List<String> {
  val manifest = httpGet("$DATA_ROOT/manifest.json") ?: return emptyList()
  val counts =
    runCatching { Json.parseToJsonElement(manifest).jsonObject["resourceCounts"]?.jsonObject }
      .getOrNull()
      ?: return emptyList()
  return counts.keys.map { "$it.ndjson" }.sorted()
}

internal actual suspend fun readDataFile(relativePath: String): String? =
  httpGet("$DATA_ROOT/$relativePath")
