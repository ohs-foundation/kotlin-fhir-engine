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
package dev.ohs.fhir.engine.benchmark.app

import dev.ohs.fhir.engine.benchmark.BenchmarkConfig
import dev.ohs.fhir.engine.benchmark.DatasetKind
import dev.ohs.fhir.engine.benchmark.Profile

/**
 * What one launch is asked to do. Each platform parses its own launch mechanism into this, so the
 * dispatch logic exists once.
 */
data class BenchmarkRequest(
  /** A single workload id, or null to run whole groups and emit a report. */
  val workloadId: String? = null,
  val groups: List<String> = listOf("crud", "search", "sync"),
  val profile: Profile = Profile.STANDARD,
  /** Synthea only resolves when the app was built with the data staged into its assets. */
  val datasetKind: DatasetKind = DatasetKind.SYNTHETIC,
  val warmupIterations: Int = 2,
  val measuredIterations: Int = 5,
) {

  fun toConfig(): BenchmarkConfig =
    BenchmarkConfig.of(
      profile = profile,
      datasetKind = datasetKind,
      warmupIterations = warmupIterations,
      measuredIterations = measuredIterations,
      groups = groups,
    )

  companion object {
    const val KEY_WORKLOAD = "workload"
    const val KEY_GROUPS = "groups"
    const val KEY_PROFILE = "profile"
    const val KEY_DATASET = "dataset"
    const val KEY_WARMUP = "warmup"
    const val KEY_ITERATIONS = "iterations"

    /** Builds a request from flat string values, whatever the platform read them from. */
    fun from(values: Map<String, String?>): BenchmarkRequest =
      BenchmarkRequest(
        workloadId = values[KEY_WORKLOAD]?.takeIf { it.isNotBlank() },
        groups =
          values[KEY_GROUPS]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("crud", "search", "sync"),
        profile = Profile.fromString(values[KEY_PROFILE]),
        datasetKind =
          if (values[KEY_DATASET].equals("synthea", ignoreCase = true)) {
            DatasetKind.SYNTHEA
          } else {
            DatasetKind.SYNTHETIC
          },
        warmupIterations = values[KEY_WARMUP]?.toIntOrNull() ?: 2,
        measuredIterations = values[KEY_ITERATIONS]?.toIntOrNull() ?: 5,
      )
  }
}
