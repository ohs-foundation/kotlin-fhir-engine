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
import kotlinx.serialization.json.Json

/** Where the run happened. Two reports from different platforms are never comparable. */
@Serializable
data class PlatformDescriptor(
  val target: String,
  val os: String,
  val deviceModel: String? = null,
  val cpuCount: Int? = null,
)

/** What the run measured against. Two reports with different fingerprints are not comparable. */
@Serializable
data class DatasetManifest(
  val kind: String,
  val population: Int,
  val seed: Int,
  val resourceCounts: Map<String, Int>,
  val fingerprint: String,
)

@Serializable
data class WorkloadResult(
  val id: String,
  val group: String,
  val opsPerIteration: Int,
  /** The isolation that actually ran, which may be weaker than the workload asked for. */
  val isolationApplied: String,
  /** Set when [isolationApplied] differs from the workload's declared isolation, and why. */
  val isolationNote: String? = null,
  val samplesMillis: List<Double>,
  val statistics: Statistics,
  val medianMillisPerOp: Double,
  /** Set when the workload could not run. Statistics are meaningless if this is non-null. */
  val error: String? = null,
)

@Serializable
data class BenchmarkReport(
  val schemaVersion: Int = SCHEMA_VERSION,
  val timestamp: String,
  val platform: PlatformDescriptor,
  val config: BenchmarkConfig,
  val dataset: DatasetManifest,
  val results: List<WorkloadResult>,
) {
  fun toJson(): String = JSON.encodeToString(serializer(), this)

  companion object {
    const val SCHEMA_VERSION = 1

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }
  }
}
