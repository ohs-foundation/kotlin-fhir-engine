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

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.benchmark.BenchmarkEnv
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.engine.search.count
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.model.r4.Basic
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Reads one Organization and one Practitioner, so SQLite's page cache holds other pages before the
 * next iteration. Ports android-fhir's version.
 */
internal suspend fun FhirEngine.evictPageCache() {
  search<Organization> { count = 1 }
  search<Practitioner> { count = 1 }
}

/** Inserts the whole dataset in chunks, since the full corpus may not fit in the heap. */
internal suspend fun BenchmarkEnv.seedDataset() {
  val chunk = ArrayList<Resource>(SEED_CHUNK)
  dataset.resources().collect { resource ->
    chunk += resource
    if (chunk.size == SEED_CHUNK) {
      engine.create(*chunk.toTypedArray())
      chunk.clear()
    }
  }
  if (chunk.isNotEmpty()) engine.create(*chunk.toTypedArray())
}

internal const val SEED_CHUNK = 500

/** Batches a stream into fixed-size lists without materialising it. */
internal fun Flow<Resource>.chunked(size: Int): Flow<List<Resource>> = flow {
  val chunk = ArrayList<Resource>(size)
  collect { resource ->
    chunk += resource
    if (chunk.size == size) {
      emit(ArrayList(chunk))
      chunk.clear()
    }
  }
  if (chunk.isNotEmpty()) emit(chunk)
}

/** What to do about a database that may or may not already hold the corpus. */
internal enum class SeedDecision {
  /** It is all there. */
  SKIP,

  /** Nothing there yet. */
  SEED,

  /** Something there, but not this corpus. Clear it first. */
  RESEED,
}

/**
 * Whether the database already holds the corpus. Only a marker matching the corpus fingerprint
 * skips: another workload may have left a subset, such as `crud.create_batch`'s patients alone.
 */
internal fun seedDecision(patients: Long, marker: String?, fingerprint: String): SeedDecision =
  when {
    marker == fingerprint -> SeedDecision.SKIP
    patients == 0L && marker == null -> SeedDecision.SEED
    else -> SeedDecision.RESEED
  }

/**
 * Seeds unless the corpus is already there in full, so read-only workloads at
 * [dev.ohs.fhir.engine.benchmark.Isolation.NONE] share one seeded database.
 */
internal suspend fun BenchmarkEnv.seedDatasetIfEmpty() {
  val fingerprint = dataset.manifest().fingerprint
  val marker =
    try {
      engine.get<Basic>(SEED_MARKER_ID).code.text?.value
    } catch (e: ResourceNotFoundException) {
      null
    }
  when (seedDecision(engine.count<Patient> {}, marker, fingerprint)) {
    SeedDecision.SKIP -> return
    SeedDecision.SEED -> seedDataset()
    SeedDecision.RESEED -> {
      engine.clearDatabase()
      seedDataset()
    }
  }
  // Written last, so a seed cut short leaves no marker.
  engine.create(
    Basic(id = SEED_MARKER_ID, code = CodeableConcept(text = FhirString(value = fingerprint))),
  )
}

private const val SEED_MARKER_ID = "benchmark-seed"

/** Shuffled so reads avoid sequential page access. Seeded, so every platform reads the same ids. */
internal fun BenchmarkEnv.crudTargetIds(limit: Int): List<String> =
  dataset.patientIds.shuffled(kotlin.random.Random(CRUD_SHUFFLE_SEED)).take(limit)

internal const val CRUD_SHUFFLE_SEED = 4242
