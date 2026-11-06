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
package dev.ohs.fhir.engine.benchmark.workloads

import kotlin.test.Test
import kotlin.test.assertEquals

/** A partially seeded database is reseeded, not treated as ready. */
class SeedDecisionTest {

  @Test
  fun `seeds an empty database`() {
    assertEquals(SeedDecision.SEED, seedDecision(present = 0, expected = 50_000))
  }

  @Test
  fun `does nothing when the corpus is already all there`() {
    assertEquals(SeedDecision.SKIP, seedDecision(present = 50_000, expected = 50_000))
  }

  @Test
  fun `starts over when a previous seed was cut short`() {
    assertEquals(SeedDecision.RESEED, seedDecision(present = 31_402, expected = 50_000))
  }

  @Test
  fun `starts over when the database holds more than the corpus`() {
    assertEquals(SeedDecision.RESEED, seedDecision(present = 60_000, expected = 50_000))
  }

  @Test
  fun `does nothing for a corpus with no patients to count`() {
    assertEquals(SeedDecision.SKIP, seedDecision(present = 0, expected = 0))
  }
}
