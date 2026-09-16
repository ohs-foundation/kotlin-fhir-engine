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

/**
 * Indexing runs on every write, evaluating one FHIRPath expression per search parameter per
 * resource. It is the engine's most likely CPU bottleneck on the write path.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceIndexerBenchmark {

  private lateinit var indexer: ResourceIndexer

  /**
   * Constructing [ResourceIndexer] builds a FHIRPath engine, which costs far more than the indexing
   * being measured, so it belongs here rather than in the timed region.
   */
  @Setup
  fun setUp() {
    indexer = ResourceIndexer(SearchParamDefinitionsProviderImpl())
    requireIndexes(Fixtures.minimalPatient)
    requireIndexes(Fixtures.richPatient)
    requireIndexes(Fixtures.observation)
  }

  // ResourceIndices is internal to the engine, so a public @Benchmark method cannot return it.
  // Blackhole consumption is what keeps the result from being optimised away instead.
  @Benchmark
  fun indexMinimalPatient(blackhole: Blackhole) =
    blackhole.consume(indexer.index(Fixtures.minimalPatient))

  @Benchmark
  fun indexRichPatient(blackhole: Blackhole) =
    blackhole.consume(indexer.index(Fixtures.richPatient))

  @Benchmark
  fun indexObservation(blackhole: Blackhole) =
    blackhole.consume(indexer.index(Fixtures.observation))

  /**
   * [ResourceIndexer] swallows FHIRPath expressions it cannot evaluate, so a fixture that stopped
   * matching any search parameter would still index cleanly — just to nothing, turning this whole
   * class into a measurement of an empty loop. Fail the run instead of reporting that number.
   */
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
      "${resource::class.simpleName} '${resource.id}' produced no search indices, so this " +
        "benchmark would measure nothing. The fixture no longer matches any R4 search parameter, " +
        "or the FHIRPath engine stopped evaluating the expressions it used to."
    }
  }
}
