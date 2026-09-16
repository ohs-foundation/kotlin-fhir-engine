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

import dev.ohs.fhir.engine.getResourceClass
import dev.ohs.fhir.engine.updateMeta
import dev.ohs.fhir.engine.withId
import dev.ohs.fhir.model.r4.Resource
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * Small helpers in `MoreResources.kt` that run once per resource on the storage paths, where a
 * per-call cost that looks negligible is multiplied by the size of the corpus.
 *
 * Two things here are worth a number rather than an argument:
 * * [getResourceClass] compiles a `Regex` on every call to strip a namespace prefix that is absent
 *   from ordinary input.
 * * [updateMeta] and [withId] set a single field by serializing the whole resource to JSON, parsing
 *   it, mutating one key and decoding it again. Their KDoc documents this as a deliberate
 *   workaround for `Resource` being immutable with no polymorphic builder, so the question is not
 *   whether it is odd but whether it is expensive enough to push upstream.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class MoreResourcesBenchmark {

  private val lastUpdated = Instant.fromEpochMilliseconds(1_766_000_000_000)

  @Benchmark fun getResourceClassByName(): KClass<Resource> = getResourceClass("Patient")

  @Benchmark
  fun updateMetaOnRichPatient(): Resource =
    Fixtures.richPatient.updateMeta(versionId = "2", lastUpdated = lastUpdated)

  @Benchmark fun withIdOnRichPatient(): Resource = Fixtures.richPatient.withId("patient-renamed")

  /**
   * The same field change on the smallest realistic resource. The gap against the rich patient is
   * the part of the cost that comes from re-encoding the whole resource rather than from the edit.
   */
  @Benchmark
  fun withIdOnMinimalPatient(): Resource = Fixtures.minimalPatient.withId("patient-renamed")
}
