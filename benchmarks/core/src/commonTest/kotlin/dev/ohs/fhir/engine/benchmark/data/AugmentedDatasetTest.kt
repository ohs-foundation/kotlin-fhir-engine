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
package dev.ohs.fhir.engine.benchmark.data

import dev.ohs.fhir.engine.benchmark.DatasetManifest
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** Patients with no clinical data, like the packaged Synthea corpus. */
private class PatientsOnly(private val count: Int) : Dataset {
  private val all: List<Resource> = (0 until count).map { Patient(id = "p$it") }

  override val population = count
  override val resourceCount = all.size

  override fun resources(): Flow<Resource> = all.asFlow()

  override val patientIds = all.map { it.id!! }
  override val sampleObservationCode = "none"
  override val sampleOrganizationId = ""
  override val sampleFamilyName = "Okonkwo"
  override val sampleGivenName = "Adaeze"

  override fun manifest() =
    DatasetManifest(
      kind = "synthea",
      population = count,
      seed = 1,
      resourceCounts = mapOf("Patient" to count),
      fingerprint = "corpusfp",
    )
}

/** Generates observations and conditions for the corpus's patient ids. */
class AugmentedDatasetTest {

  private val mix = ClinicalMix(observations = 8, conditions = 2)

  private fun augmented(patients: Int) = AugmentedDataset(PatientsOnly(patients), mix, seed = 7)

  @Test
  fun `parses a mix from its compact form`() {
    assertEquals(ClinicalMix(observations = 8, conditions = 2), ClinicalMix.parse("8x2"))
    assertEquals(ClinicalMix(observations = 4, conditions = 0), ClinicalMix.parse("4x0"))
  }

  @Test
  fun `parses off as no generation at all`() {
    // `off` leaves the corpus as packaged.
    assertEquals(ClinicalMix(observations = 0, conditions = 0), ClinicalMix.parse("off"))
    assertEquals(0, ClinicalMix.parse("off").perPatient)
  }

  @Test
  fun `rejects a malformed mix instead of guessing`() {
    assertFailsWith<IllegalArgumentException> { ClinicalMix.parse("8") }
    assertFailsWith<IllegalArgumentException> { ClinicalMix.parse("axb") }
  }

  @Test
  fun `falls back to the default for a blank mix`() {
    assertEquals(ClinicalMix(), ClinicalMix.parse(""))
    assertEquals(ClinicalMix(), ClinicalMix.parse(null))
  }

  @Test
  fun `counts the corpus and what was generated for it`() {
    assertEquals(3 + 3 * 10, augmented(3).resourceCount)
  }

  @Test
  fun `yields every corpus resource before any generated one`() = runTest {
    val ids = augmented(2).resources().toList().map { it.id }

    assertEquals(listOf("p0", "p1"), ids.take(2))
    assertEquals(22, ids.size)
  }

  @Test
  fun `generates the configured mix for each patient`() = runTest {
    val generated = augmented(3).resources().toList()

    assertEquals(24, generated.filterIsInstance<Observation>().size)
    assertEquals(6, generated.filterIsInstance<Condition>().size)
  }

  @Test
  fun `points every generated resource at a patient in the corpus`() = runTest {
    val subjects =
      augmented(3).resources().toList().mapNotNull {
        when (it) {
          is Observation -> it.subject?.reference?.value
          is Condition -> it.subject?.reference?.value
          else -> null
        }
      }

    assertEquals(30, subjects.size)
    assertTrue(subjects.all { it in listOf("Patient/p0", "Patient/p1", "Patient/p2") }, "$subjects")
  }

  @Test
  fun `gives every generated resource its own id`() = runTest {
    val ids = augmented(4).resources().toList().mapNotNull { it.id }

    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `reports an observation code the generated data actually uses`() = runTest {
    val dataset = augmented(3)
    val codes =
      dataset.resources().toList().filterIsInstance<Observation>().mapNotNull {
        it.code.coding.firstOrNull()?.code?.value
      }

    // The token search workloads query this code.
    assertTrue(dataset.sampleObservationCode in codes, dataset.sampleObservationCode)
  }

  @Test
  fun `records the generated types in the manifest`() {
    val manifest = augmented(3).manifest()

    assertEquals(3, manifest.resourceCounts["Patient"])
    assertEquals(24, manifest.resourceCounts["Observation"])
    assertEquals(6, manifest.resourceCounts["Condition"])
    // Generated data makes it a different dataset.
    assertTrue(manifest.fingerprint != "corpusfp", manifest.fingerprint)
  }

  @Test
  fun `generates identically for the same seed`() = runTest {
    val first = augmented(3).resources().toList().map { it.id }
    val second = augmented(3).resources().toList().map { it.id }

    assertEquals(first, second)
  }
}
