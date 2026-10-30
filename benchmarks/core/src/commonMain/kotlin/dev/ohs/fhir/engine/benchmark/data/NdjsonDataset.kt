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
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Synthea bulk data: one `.ndjson` per resource type, one resource per line. Built by [load].
 *
 * Parsed with unknown keys ignored, since Synthea emits extensions the model does not carry.
 * [parseFailures] lists the lines that still failed.
 */
class NdjsonDataset
private constructor(
  private val orderedFiles: List<String>,
  private val lines: (String) -> Flow<String>,
  private val counts: Map<String, Int>,
  override val patientIds: List<String>,
  override val sampleObservationCode: String,
  override val sampleOrganizationId: String,
  override val sampleFamilyName: String,
  override val sampleGivenName: String,
  val parseFailures: List<String>,
  private val seed: Int,
  private val fingerprint: String,
) : Dataset {

  override val population: Int = patientIds.size

  override val resourceCount: Int = counts.values.sum()

  /**
   * Re-parses the corpus on every call, in the order [load] fingerprinted. Lines that fail to parse
   * are skipped.
   */
  override fun resources(): Flow<Resource> = flow {
    for (name in orderedFiles) {
      lines(name).collect { line ->
        if (line.isBlank()) return@collect
        val resource =
          try {
            JSON.decodeFromString<Resource>(line)
          } catch (e: Exception) {
            null
          }
        if (resource != null) emit(resource)
      }
    }
  }

  override fun manifest() =
    DatasetManifest(
      kind = "synthea",
      population = population,
      seed = seed,
      resourceCounts = counts,
      fingerprint = fingerprint,
    )

  companion object {
    private val JSON = Json {
      explicitNulls = false
      encodeDefaults = false
      ignoreUnknownKeys = true
    }

    /**
     * Scans the corpus once for counts, ids, samples and a fingerprint, without keeping resources.
     * Reuses the scan from [cache] under [cacheKey] when present.
     *
     * @param lines streams the lines of a named file.
     */
    suspend fun load(
      fileNames: List<String>,
      seed: Int,
      requestedPopulation: Int,
      lines: (String) -> Flow<String>,
      cache: DatasetCache? = null,
      cacheKey: String? = null,
    ): NdjsonDataset {
      val ordered = inLoadOrder(fileNames)
      cached(cache, cacheKey)?.let { metadata ->
        return NdjsonDataset(
          orderedFiles = ordered,
          lines = lines,
          counts = metadata.counts,
          patientIds = metadata.patientIds,
          sampleObservationCode = metadata.sampleObservationCode,
          sampleFamilyName = metadata.sampleFamilyName,
          sampleGivenName = metadata.sampleGivenName,
          sampleOrganizationId = metadata.sampleOrganizationId,
          // Failures are reported by the run that wrote the cache.
          parseFailures = emptyList(),
          seed = seed,
          fingerprint = metadata.fingerprint,
        )
      }
      val counts = mutableMapOf<String, Int>()
      val patientIds = mutableListOf<String>()
      val observationCodes = mutableMapOf<String, Int>()
      var organizationId = ""
      var familyName = ""
      var givenName = ""
      val parseFailures = mutableListOf<String>()
      var hash = 17L

      for (name in ordered) {
        val type = name.substringBefore(".")
        var count = 0
        lines(name).collect { line ->
          if (line.isBlank()) return@collect
          val resource =
            try {
              JSON.decodeFromString<Resource>(line)
            } catch (e: Exception) {
              parseFailures += "$name: ${e::class.simpleName}: ${e.message?.take(160)}"
              return@collect
            }
          count++
          hash = hash * 31 + resource.id.hashCode()
          hash = hash * 31 + resource::class.simpleName.hashCode()
          when (resource) {
            is Patient -> {
              resource.id?.let { patientIds += it }
              // Synthea names are varied enough that any one is a selective search.
              if (familyName.isEmpty()) {
                resource.name.firstOrNull()?.let { name ->
                  familyName = name.family?.value.orEmpty()
                  givenName = name.given.firstOrNull()?.value.orEmpty()
                }
              }
            }
            is Organization -> if (organizationId.isEmpty()) organizationId = resource.id ?: ""
            is Observation ->
              observationCode(resource)?.let {
                observationCodes[it] = (observationCodes[it] ?: 0) + 1
              }
            else -> Unit
          }
        }
        // A type can be split across several numbered files.
        counts[type] = (counts[type] ?: 0) + count
      }

      hash = hash * 31 + requestedPopulation
      hash = hash * 31 + seed
      val fingerprint = hash.toULong().toString(16).padStart(16, '0')
      // Synthea's observation codes vary with the seed, so the commonest is discovered.
      val code = observationCodes.maxByOrNull { it.value }?.key ?: "8867-4"
      if (cache != null && cacheKey != null) {
        cache.write(
          cacheKey,
          JSON.encodeToString(
            DatasetMetadata(
              counts = counts,
              patientIds = patientIds,
              sampleObservationCode = code,
              sampleOrganizationId = organizationId,
              sampleFamilyName = familyName,
              sampleGivenName = givenName,
              fingerprint = fingerprint,
            ),
          ),
        )
      }
      return NdjsonDataset(
        orderedFiles = ordered,
        lines = lines,
        counts = counts,
        patientIds = patientIds,
        sampleObservationCode = code,
        sampleOrganizationId = organizationId,
        sampleFamilyName = familyName,
        sampleGivenName = givenName,
        parseFailures = parseFailures,
        seed = seed,
        fingerprint = fingerprint,
      )
    }

    /** A previous scan, or null to scan again. An unreadable entry counts as a miss. */
    private suspend fun cached(cache: DatasetCache?, key: String?): DatasetMetadata? {
      if (cache == null || key == null) return null
      val stored = cache.read(key) ?: return null
      return try {
        JSON.decodeFromString<DatasetMetadata>(stored)
      } catch (e: Exception) {
        null
      }
    }

    /** Referenced types first, so references resolve as the corpus is inserted. */
    private fun inLoadOrder(fileNames: List<String>): List<String> =
      fileNames
        .filter { it.substringBefore(".") in INCLUDED_TYPES }
        .sortedBy {
          val index = LOAD_ORDER.indexOf(it.substringBefore("."))
          if (index < 0) LOAD_ORDER.size else index
        }

    private fun observationCode(observation: Observation): String? =
      observation.code.coding.firstOrNull()?.code?.value

    /** The types the workloads touch; Synthea's larger billing and document types are skipped. */
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
