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
  /** Exact workloads to run. When set, [groups] is ignored. */
  val workloadIds: List<String> = emptyList(),
  /** Clinical resources generated per patient, `<observations>x<conditions>` or `off`. */
  val clinicalMix: String = "8x2",
  /** Reopen the database between iterations of read-only workloads to measure a cold page cache. */
  val coldCache: Boolean = false,
  /** Patients to generate, overriding [profile]. Synthetic datasets only. */
  val population: Int? = null,
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
      workloadIds: List<String> = emptyList(),
      clinicalMix: String = "8x2",
      coldCache: Boolean = false,
      population: Int? = null,
    ) =
      BenchmarkConfig(
        profile = profile.name.lowercase(),
        datasetKind = datasetKind.name.lowercase(),
        seed = seed,
        warmupIterations = warmupIterations,
        measuredIterations = measuredIterations,
        groups = groups,
        // Ktor drops the last path segment of a base URL without a trailing slash.
        serverUrl = serverUrl?.trim()?.takeIf { it.isNotEmpty() }?.removeSuffix("/")?.plus("/"),
        workloadIds = workloadIds,
        clinicalMix = clinicalMix,
        coldCache = coldCache,
        population = population,
      )

    const val DEFAULT_SEED = 20260819
  }

  /** [population] if set, else the profile's. */
  val effectivePopulation: Int
    get() = population ?: Profile.fromString(profile).population
}
