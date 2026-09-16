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

import dev.ohs.fhir.engine.db.impl.JsonDiff
import dev.ohs.fhir.engine.db.impl.serializeResource
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.String as FhirString
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * [JsonDiff] produces the RFC 6902 patch behind every local update. Its KDoc records that it is a
 * hand-written replacement for the JVM-only Jackson + jsonpatch pairing, so nothing has ever
 * compared its cost to what it replaced.
 *
 * Both a one-field edit and a wholesale rewrite are measured: the diff walks both trees in full
 * regardless, so the gap between them shows how much of the cost is traversal rather than patch
 * construction.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class JsonDiffBenchmark {

  private lateinit var original: String
  private lateinit var oneFieldChanged: String
  private lateinit var heavilyChanged: String

  @Setup
  fun setUp() {
    original = serializeResource(Fixtures.richPatient)
    oneFieldChanged =
      serializeResource(Fixtures.richPatient.copy(active = FhirBoolean(value = false)))
    // A different family name on every entry, so the diff has to emit an op per name.
    heavilyChanged =
      serializeResource(
        Fixtures.richPatient.copy(
          name =
            Fixtures.richPatient.name.mapIndexed { index, humanName ->
              HumanName(
                family = FhirString(value = "Renamed-$index"),
                given = humanName.given,
              )
            },
        ),
      )
    // An empty patch would mean the fixtures are accidentally identical, leaving these benchmarks
    // measuring the cost of finding no differences under names that promise otherwise.
    check(JsonDiff.diff(original, oneFieldChanged) != EMPTY_PATCH) {
      "oneFieldChanged is identical to the original, so diffOneFieldChanged measures nothing."
    }
    check(JsonDiff.diff(original, heavilyChanged) != EMPTY_PATCH) {
      "heavilyChanged is identical to the original, so diffHeavilyChanged measures nothing."
    }
  }

  @Benchmark fun diffOneFieldChanged(): String = JsonDiff.diff(original, oneFieldChanged)

  @Benchmark fun diffHeavilyChanged(): String = JsonDiff.diff(original, heavilyChanged)

  /** The cost of proving two identical resources have no differences. */
  @Benchmark fun diffUnchanged(): String = JsonDiff.diff(original, original)

  private companion object {
    const val EMPTY_PATCH = "[]"
  }
}
