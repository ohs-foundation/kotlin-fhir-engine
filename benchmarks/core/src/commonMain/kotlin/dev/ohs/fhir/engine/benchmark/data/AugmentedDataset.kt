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
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How much clinical data to build for each patient in the corpus. */
data class ClinicalMix(val observations: Int = 8, val conditions: Int = 2) {
  val perPatient: Int
    get() = observations + conditions

  companion object {
    /**
     * Parses `<observations>x<conditions>` (e.g. `8x2`) or `off` for none; blank gives the default.
     * Malformed input throws so a typo cannot silently change what a run measures.
     */
    fun parse(value: String?): ClinicalMix {
      val trimmed = value?.trim().orEmpty()
      if (trimmed.isEmpty()) return ClinicalMix()
      if (trimmed.equals("off", ignoreCase = true)) return ClinicalMix(0, 0)
      val parts = trimmed.lowercase().split("x")
      require(parts.size == 2) {
        "benchmark.mix must be <observations>x<conditions> or off: $trimmed"
      }
      val observations = requireNotNull(parts[0].toIntOrNull()) { "Not a number: ${parts[0]}" }
      val conditions = requireNotNull(parts[1].toIntOrNull()) { "Not a number: ${parts[1]}" }
      return ClinicalMix(observations, conditions)
    }
  }
}

/**
 * A Synthea corpus plus generated observations and conditions for each of its patients.
 *
 * Every generated resource references a corpus patient id, so joins and reverse-includes resolve
 * against real rows.
 */
class AugmentedDataset(
  private val base: Dataset,
  private val mix: ClinicalMix = ClinicalMix(),
  private val seed: Int = 0,
) : Dataset {

  override val population: Int = base.population

  override val resourceCount: Int = base.resourceCount + base.patientIds.size * mix.perPatient

  override val patientIds: List<String> = base.patientIds

  override val sampleOrganizationId: String = base.sampleOrganizationId

  /** A generated code; the base corpus carries no observations. */
  override val sampleObservationCode: String = OBSERVATION_CODES.first()

  override val sampleFamilyName: String = base.sampleFamilyName

  override val sampleGivenName: String = base.sampleGivenName

  /** Corpus first, so patients exist before anything references them. */
  override fun resources(): Flow<Resource> = flow {
    base.resources().collect { emit(it) }
    // Emitted as generated so at most one resource is resident.
    for ((index, patientId) in base.patientIds.withIndex()) {
      val random = Random(seed * 31 + index)
      repeat(mix.observations) { slot -> emit(observation(patientId, index, slot, random)) }
      repeat(mix.conditions) { slot -> emit(condition(patientId, index, slot)) }
    }
  }

  override fun manifest(): DatasetManifest {
    val counts = base.manifest().resourceCounts.toMutableMap()
    val patients = base.patientIds.size
    counts["Observation"] = (counts["Observation"] ?: 0) + patients * mix.observations
    counts["Condition"] = (counts["Condition"] ?: 0) + patients * mix.conditions
    return base
      .manifest()
      .copy(
        kind = "${base.manifest().kind}+generated",
        resourceCounts = counts,
        // The same corpus with a different mix or seed is a different dataset.
        fingerprint = "${base.manifest().fingerprint}-g${mix.observations}x${mix.conditions}s$seed",
      )
  }

  private fun observation(patientId: String, index: Int, slot: Int, random: Random) =
    Observation(
      id = "bench-observation-$index-$slot",
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = LOINC_SYSTEM),
                code = Code(value = OBSERVATION_CODES[(index + slot) % OBSERVATION_CODES.size]),
              ),
            ),
        ),
      subject = Reference(reference = FhirString(value = "Patient/$patientId")),
      // Spread so the quantity range query matches some rows, not all or none.
      value =
        Observation.Value.Quantity(
          Quantity(
            value = Decimal(value = FhirDecimal.fromInt(40 + random.nextInt(120))),
            unit = FhirString(value = "mg"),
            system = Uri(value = UCUM_SYSTEM),
            code = Code(value = "mg"),
          ),
        ),
    )

  private fun condition(patientId: String, index: Int, slot: Int) =
    Condition(
      id = "bench-condition-$index-$slot",
      code =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = SNOMED_SYSTEM),
                code = Code(value = CONDITION_CODES[(index + slot) % CONDITION_CODES.size]),
              ),
            ),
        ),
      subject = Reference(reference = FhirString(value = "Patient/$patientId")),
    )

  companion object {
    /** Shared with [SyntheticDataset] so a query means the same thing whichever dataset ran. */
    private val OBSERVATION_CODES = SyntheticDataset.OBSERVATION_CODES

    private val CONDITION_CODES = SyntheticDataset.CONDITION_CODES

    private const val LOINC_SYSTEM = SyntheticDataset.LOINC_SYSTEM
    private const val SNOMED_SYSTEM = SyntheticDataset.SNOMED_SYSTEM
    private const val UCUM_SYSTEM = SyntheticDataset.UCUM_SYSTEM
  }
}
