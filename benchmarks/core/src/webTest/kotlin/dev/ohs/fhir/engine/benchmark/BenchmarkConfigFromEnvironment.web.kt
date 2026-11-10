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

/**
 * Fetches the `-Pbenchmark.*` values from the test server in `karma.config.d/benchmark-server.js`.
 * Falls back to defaults when the config cannot be fetched or parsed. The defaults are small
 * because a browser writing to OPFS is the slowest target.
 */
internal actual suspend fun benchmarkConfigFromEnvironment(): BenchmarkConfig {
  val json = httpGet("/benchmark-config") ?: return browserDefaults()
  return runCatching { lenientJson.decodeFromString<BenchmarkConfig>(json) }
    .getOrElse { browserDefaults() }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun browserDefaults() =
  BenchmarkConfig.of(profile = Profile.SMOKE, warmupIterations = 1, measuredIterations = 3)
