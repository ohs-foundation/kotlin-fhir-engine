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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatisticsTest {

  @Test
  fun `standard deviation treats samples as a sample, not a population`() {
    // The population standard deviation of these samples is 2.0.
    val statistics = Statistics.of(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))

    assertEquals(2.1380899, statistics.stdDev, TOLERANCE)
  }

  @Test
  fun `standard deviation of a single sample is zero rather than undefined`() {
    // Bessel's correction divides by n-1, which is zero here.
    val statistics = Statistics.of(listOf(7.0))

    assertEquals(0.0, statistics.stdDev, TOLERANCE)
  }

  @Test
  fun `p90 is absent when there are too few samples to have a top decile`() {
    // A p90 over five samples is effectively the maximum.
    val statistics = Statistics.of(listOf(1.0, 2.0, 3.0, 4.0, 100.0))

    assertNull(statistics.p90)
  }

  @Test
  fun `p90 is reported once there are ten samples`() {
    val statistics = Statistics.of((1..10).map { it.toDouble() })

    // Rank 0.9 * (10 - 1) = 8.1.
    assertEquals(9.1, assertNotNull(statistics.p90), TOLERANCE)
  }

  @Test
  fun `median interpolates between the two middle samples of an even sample count`() {
    val statistics = Statistics.of(listOf(4.0, 1.0, 3.0, 2.0))

    assertEquals(2.5, statistics.median, TOLERANCE)
  }

  @Test
  fun `median of an odd sample count is the middle sample`() {
    val statistics = Statistics.of(listOf(3.0, 1.0, 2.0))

    assertEquals(2.0, statistics.median, TOLERANCE)
  }

  @Test
  fun `min max and mean summarise unsorted samples`() {
    val statistics = Statistics.of(listOf(5.0, 1.0, 9.0, 5.0))

    assertEquals(1.0, statistics.min, TOLERANCE)
    assertEquals(9.0, statistics.max, TOLERANCE)
    assertEquals(5.0, statistics.mean, TOLERANCE)
  }

  @Test
  fun `summarising no samples is rejected`() {
    assertFailsWith<IllegalArgumentException> { Statistics.of(emptyList()) }
  }

  private companion object {
    const val TOLERANCE = 1e-6
  }
}
