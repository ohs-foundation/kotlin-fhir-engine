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
    assertEquals(SeedDecision.SEED, seedDecision(patients = 0, marker = null, fingerprint = "a"))
  }

  @Test
  fun `does nothing when the marker matches the corpus`() {
    assertEquals(
      SeedDecision.SKIP,
      seedDecision(patients = 50_000, marker = "a", fingerprint = "a"),
    )
  }

  @Test
  fun `starts over when a previous seed was cut short`() {
    assertEquals(
      SeedDecision.RESEED,
      seedDecision(patients = 31_402, marker = null, fingerprint = "a"),
    )
  }

  @Test
  fun `starts over when another workload left only patients`() {
    assertEquals(
      SeedDecision.RESEED,
      seedDecision(patients = 50_000, marker = null, fingerprint = "a"),
    )
  }

  @Test
  fun `starts over when the database holds a different corpus`() {
    assertEquals(SeedDecision.RESEED, seedDecision(patients = 100, marker = "b", fingerprint = "a"))
  }
}
