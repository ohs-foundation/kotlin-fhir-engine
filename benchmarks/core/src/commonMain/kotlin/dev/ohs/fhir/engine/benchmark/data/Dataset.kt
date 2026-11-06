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
import dev.ohs.fhir.model.r4.Resource
import kotlinx.coroutines.flow.Flow

/**
 * The resources a benchmark run works against.
 *
 * Implementations must be deterministic for a given seed and population: a report is only worth
 * comparing against another report built from an identical [DatasetManifest.fingerprint].
 */
interface Dataset {
  val population: Int

  /** Total resources across every type. */
  val resourceCount: Int

  /**
   * Every resource, referenced resources before the ones referencing them.
   *
   * A flow because a parsed corpus can exceed a phone's heap.
   */
  fun resources(): Flow<Resource>

  /** Patient ids in insertion order. */
  val patientIds: List<String>

  /** A [code] value that matches roughly one observation per patient. */
  val sampleObservationCode: String

  /** An organization id that a meaningful share of patients reference. */
  val sampleOrganizationId: String

  /**
   * A family name to search for. This and [sampleGivenName] must each match a small fraction of the
   * corpus, or the search benchmarks cannot detect an index regression.
   */
  val sampleFamilyName: String

  val sampleGivenName: String

  fun manifest(): DatasetManifest
}
