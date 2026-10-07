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

import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.model.r4.Resource
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/** Measures [ResourceIndexer], which evaluates one FHIRPath expression per search parameter. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceIndexerBenchmark {

  private lateinit var indexer: ResourceIndexer

  /** Constructing [ResourceIndexer] costs far more than one indexing call, so it stays untimed. */
  @Setup
  fun setUp() {
    indexer = ResourceIndexer(SearchParamDefinitionsProviderImpl())
    requireIndexes(Fixtures.minimalPatient)
    requireIndexes(Fixtures.richPatient)
    requireIndexes(Fixtures.observation)
  }

  // ResourceIndices is internal, so a public @Benchmark cannot return it.
  @Benchmark
  fun indexMinimalPatient(blackhole: Blackhole) =
    blackhole.consume(indexer.index(Fixtures.minimalPatient))

  @Benchmark
  fun indexRichPatient(blackhole: Blackhole) =
    blackhole.consume(indexer.index(Fixtures.richPatient))

  @Benchmark
  fun indexObservation(blackhole: Blackhole) =
    blackhole.consume(indexer.index(Fixtures.observation))

  /** [ResourceIndexer] skips expressions it cannot evaluate, so an empty result is silent. */
  private fun requireIndexes(resource: Resource) {
    val indices = indexer.index(resource)
    val total =
      indices.numberIndices.size +
        indices.dateIndices.size +
        indices.dateTimeIndices.size +
        indices.stringIndices.size +
        indices.uriIndices.size +
        indices.tokenIndices.size +
        indices.quantityIndices.size +
        indices.referenceIndices.size +
        indices.positionIndices.size
    check(total > 0) {
      "${resource::class.simpleName} '${resource.id}' produced no search indices. The fixture no " +
        "longer matches any R4 search parameter, or the FHIRPath engine stopped evaluating the " +
        "expressions it used to."
    }
  }
}
