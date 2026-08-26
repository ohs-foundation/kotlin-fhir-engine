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

import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** Summary of a workload's measured samples, in milliseconds. */
@Serializable
data class Statistics(
  val min: Double,
  val median: Double,
  val p90: Double,
  val max: Double,
  val mean: Double,
  val stdDev: Double,
) {
  companion object {
    fun of(samplesMillis: List<Double>): Statistics {
      require(samplesMillis.isNotEmpty()) { "Cannot summarise an empty sample list." }
      val sorted = samplesMillis.sorted()
      val mean = sorted.sum() / sorted.size
      val variance = sorted.sumOf { (it - mean) * (it - mean) } / sorted.size
      return Statistics(
        min = sorted.first(),
        median = sorted.percentile(0.50),
        p90 = sorted.percentile(0.90),
        max = sorted.last(),
        mean = mean,
        stdDev = sqrt(variance),
      )
    }

    /** Linear interpolation between the two nearest ranks. */
    private fun List<Double>.percentile(fraction: Double): Double {
      if (size == 1) return first()
      val rank = fraction * (size - 1)
      val lower = rank.toInt()
      val upper = minOf(lower + 1, size - 1)
      return this[lower] + (this[upper] - this[lower]) * (rank - lower)
    }
  }
}
