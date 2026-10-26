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

import dev.ohs.fhir.engine.search.Order
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.filter.TokenFilterValue
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.engine.search.has
import dev.ohs.fhir.engine.search.query.XFhirQueryTranslator
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * Measures translating a [Search] into SQL. Each benchmark builds its [Search] inside the timed
 * region, as callers build a new one per query.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SearchQueryBenchmark {

  @Benchmark
  fun singleStringFilter(): SearchQuery =
    Search(type = ResourceType.Patient)
      .apply { filter(StringClientParam("given"), { value = "Ada" }) }
      .getQuery()

  @Benchmark
  fun manyFilters(): SearchQuery =
    Search(type = ResourceType.Patient)
      .apply {
        filter(StringClientParam("given"), { value = "Ada" })
        filter(StringClientParam("family"), { value = "Okonkwo" })
        filter(TokenClientParam("gender"), { value = TokenFilterValue.string("female") })
        filter(TokenClientParam("active"), { value = of(true) })
        filter(
          ReferenceClientParam("organization"),
          { value = "Organization/organization-1" },
        )
      }
      .getQuery()

  @Benchmark
  fun sortedAndPaged(): SearchQuery =
    Search(type = ResourceType.Patient, count = 50, from = 100)
      .apply { sort(StringClientParam("given"), Order.ASCENDING) }
      .getQuery()

  /** Reverse chaining embeds a nested query. */
  @Benchmark
  fun nestedHasAndRevInclude(): SearchQuery =
    Search(type = ResourceType.Patient)
      .apply {
        has<Condition>(ReferenceClientParam("subject")) {
          filter(TokenClientParam("code"), { value = TokenFilterValue.string("44054006") })
        }
        revInclude<Observation>(ReferenceClientParam("subject"))
      }
      .getQuery()

  /** The `COUNT(*)` variant, a different branch through the builder. */
  @Benchmark
  fun countQuery(): SearchQuery =
    Search(type = ResourceType.Patient)
      .apply { filter(StringClientParam("given"), { value = "Ada" }) }
      .getQuery(isCount = true)

  /** Parses an x-fhir-query string, which looks up a search parameter definition per term. */
  @Benchmark
  fun translateXFhirQuery(): Search =
    XFhirQueryTranslator.translate("Patient?active=true&gender=male&_sort=family&_count=11")
}
