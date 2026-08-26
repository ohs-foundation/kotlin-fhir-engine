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
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.json.Json

/**
 * Synthea bulk data: one `.ndjson` per resource type, one resource per line.
 *
 * Parsed leniently. Synthea emits US Core profiles and extensions the model does not carry, and a
 * strict parse would reject most of the corpus; unknown keys are dropped instead. [parseFailures]
 * records the lines that still failed so a half-loaded dataset is visible rather than silent.
 */
class NdjsonDataset(
  private val files: Map<String, String>,
  private val seed: Int,
  private val requestedPopulation: Int,
) : Dataset {

  val parseFailures: MutableList<String> = mutableListOf()

  private val byType: Map<String, List<Resource>> = parse()

  override val allResources: List<Resource> =
    // Referenced types first, so references resolve as they are inserted.
    LOAD_ORDER.flatMap { byType[it].orEmpty() } +
      byType.filterKeys { it !in LOAD_ORDER }.values.flatten()

  private val patients = byType["Patient"].orEmpty().filterIsInstance<Patient>()

  override val population: Int = patients.size

  override val patientIds: List<String> = patients.mapNotNull { it.id }

  /**
   * The most common Observation code in the corpus, so the token query matches a useful share of
   * rows rather than none. Synthea's mix varies with the seed, hence discovering it rather than
   * hard-coding.
   */
  override val sampleObservationCode: String =
    byType["Observation"]
      .orEmpty()
      .mapNotNull { observationCode(it) }
      .groupingBy { it }
      .eachCount()
      .maxByOrNull { it.value }
      ?.key
      ?: "8867-4"

  override val sampleOrganizationId: String =
    byType["Organization"].orEmpty().filterIsInstance<Organization>().firstNotNullOfOrNull { it.id }
      ?: ""

  override fun manifest() =
    DatasetManifest(
      kind = "synthea",
      population = population,
      seed = seed,
      resourceCounts = byType.mapValues { (_, resources) -> resources.size },
      fingerprint = fingerprint(),
    )

  private fun parse(): Map<String, List<Resource>> = buildMap {
    for ((name, contents) in files) {
      val type = name.substringBefore(".")
      if (type !in INCLUDED_TYPES) continue
      val resources =
        contents
          .lineSequence()
          .filter { it.isNotBlank() }
          .mapNotNull { line ->
            try {
              JSON.decodeFromString<Resource>(line)
            } catch (e: Exception) {
              parseFailures += "$name: ${e::class.simpleName}: ${e.message?.take(160)}"
              null
            }
          }
      put(type, get(type).orEmpty() + resources.toList())
    }
  }

  private fun observationCode(resource: Resource): String? =
    (resource as? dev.ohs.fhir.model.r4.Observation)?.code?.coding?.firstOrNull()?.code?.value

  private fun fingerprint(): String {
    var hash = 17L
    for (resource in allResources) {
      hash = hash * 31 + resource.id.hashCode()
      hash = hash * 31 + resource::class.simpleName.hashCode()
    }
    hash = hash * 31 + requestedPopulation
    hash = hash * 31 + seed
    return hash.toULong().toString(16).padStart(16, '0')
  }

  companion object {
    private val JSON = Json {
      explicitNulls = false
      encodeDefaults = false
      ignoreUnknownKeys = true
    }

    /**
     * The types the workloads touch. Synthea also emits Claim, ExplanationOfBenefit and
     * DocumentReference, which together dwarf everything else and none of the queries look at.
     */
    val INCLUDED_TYPES =
      setOf(
        "Organization",
        "Practitioner",
        "Patient",
        "Encounter",
        "Condition",
        "Observation",
        "Procedure",
        "Immunization",
        "AllergyIntolerance",
        "MedicationRequest",
        "CarePlan",
      )

    private val LOAD_ORDER = listOf("Organization", "Practitioner", "Patient", "Encounter")
  }
}
