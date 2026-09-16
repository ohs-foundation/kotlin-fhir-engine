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
package dev.ohs.fhir.engine.microbenchmark

import dev.ohs.fhir.engine.db.impl.deserializeResource
import dev.ohs.fhir.engine.db.impl.serializeResource
import dev.ohs.fhir.model.r4.Resource
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Every read and every write round-trips through these two functions, so their cost is a floor
 * under all of the engine's storage paths.
 *
 * Most of what this measures belongs to `fhir-model-r4` and kotlinx.serialization rather than to
 * the engine. It is here because the floor is worth knowing regardless of who owns it, and because
 * the `explicitNulls`/`encodeDefaults` settings in `ResourceSerializer.kt` are the engine's own
 * choice — a number here is what would justify revisiting them.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceSerializerBenchmark {

  private lateinit var minimalPatientJson: String
  private lateinit var richPatientJson: String
  private lateinit var observationJson: String

  @Setup
  fun setUp() {
    minimalPatientJson = serializeResource(Fixtures.minimalPatient)
    richPatientJson = serializeResource(Fixtures.richPatient)
    observationJson = serializeResource(Fixtures.observation)
  }

  @Benchmark fun serializeMinimalPatient(): String = serializeResource(Fixtures.minimalPatient)

  @Benchmark fun serializeRichPatient(): String = serializeResource(Fixtures.richPatient)

  @Benchmark fun serializeObservation(): String = serializeResource(Fixtures.observation)

  @Benchmark fun deserializeMinimalPatient(): Resource = deserializeResource(minimalPatientJson)

  @Benchmark fun deserializeRichPatient(): Resource = deserializeResource(richPatientJson)

  @Benchmark fun deserializeObservation(): Resource = deserializeResource(observationJson)
}
