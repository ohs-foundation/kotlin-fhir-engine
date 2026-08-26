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

import kotlinx.serialization.Serializable

/** Dataset sizes. Population is patient count; resource count is roughly 20-200x that. */
enum class Profile(val population: Int) {
  SMOKE(10),
  STANDARD(100),
  LARGE(1000),
  ;

  companion object {
    fun fromString(value: String?): Profile =
      entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STANDARD
  }
}

/** Which dataset backs the run. Recorded in the report; never compare numbers across kinds. */
enum class DatasetKind {
  /** Deterministic resources generated in common Kotlin. Runs anywhere, no tooling needed. */
  SYNTHETIC,

  /** Synthea-generated bulk ndjson. The dataset for headline numbers. */
  SYNTHEA,
}

@Serializable
data class BenchmarkConfig(
  val profile: String,
  val datasetKind: String,
  val seed: Int,
  val warmupIterations: Int,
  val measuredIterations: Int,
  val groups: List<String>,
  /** Base URL of a FHIR server, or null. Only the `server` group needs one. */
  val serverUrl: String? = null,
) {
  companion object {
    fun of(
      profile: Profile = Profile.STANDARD,
      datasetKind: DatasetKind = DatasetKind.SYNTHETIC,
      seed: Int = DEFAULT_SEED,
      warmupIterations: Int = 2,
      measuredIterations: Int = 5,
      groups: List<String> = listOf("crud", "search", "sync"),
      serverUrl: String? = null,
    ) =
      BenchmarkConfig(
        profile = profile.name.lowercase(),
        datasetKind = datasetKind.name.lowercase(),
        seed = seed,
        warmupIterations = warmupIterations,
        measuredIterations = measuredIterations,
        groups = groups,
        // Ktor resolves a request path against the base URL, so a base without a trailing slash
        // loses its last segment: `.../fhir` + `Patient?...` requests `.../Patient` and 404s.
        // Uploads hide it, because a transaction bundle posts to the base itself.
        serverUrl = serverUrl?.trim()?.takeIf { it.isNotEmpty() }?.removeSuffix("/")?.plus("/"),
      )

    const val DEFAULT_SEED = 20260819
  }
}
