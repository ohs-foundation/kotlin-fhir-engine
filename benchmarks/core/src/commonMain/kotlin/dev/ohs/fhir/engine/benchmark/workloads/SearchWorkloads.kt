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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.benchmark.BenchmarkEnv
import dev.ohs.fhir.engine.benchmark.Isolation
import dev.ohs.fhir.engine.benchmark.Workload
import dev.ohs.fhir.engine.search.DateClientParam
import dev.ohs.fhir.engine.search.Order
import dev.ohs.fhir.engine.search.QuantityClientParam
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.count
import dev.ohs.fhir.engine.search.filter.TokenFilterValue
import dev.ohs.fhir.engine.search.has
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.terminologies.ResourceType

/**
 * Search DSL workloads, porting android-fhir's `SearchApiViewModel` and keeping its ids where an
 * equivalent query exists. Read-only, so they need [evictPageCache] rather than isolation.
 */
object SearchWorkloads {

  fun all(): List<Workload> =
    listOf(
      search("search.patient_by_given_prefix") { env ->
        env.engine.search<Patient> { filter(StringClientParam("given"), { value = "Ja" }) }
      },
      search("search.patient_by_family") { env ->
        env.engine.search<Patient> { filter(StringClientParam("family"), { value = "Smith" }) }
      },
      search("search.patient_by_gender_token") { env ->
        env.engine.search<Patient> {
          filter(TokenClientParam("gender"), { value = TokenFilterValue.string("male") })
        }
      },
      search("search.patient_by_active_token") { env ->
        env.engine.search<Patient> { filter(TokenClientParam("active"), { value = of(true) }) }
      },
      search("search.patient_birthdate_range") { env ->
        env.engine.search<Patient> {
          filter(
            DateClientParam("birthdate"),
            {
              prefix = SearchComparator.Gt
              value = of(FhirDate.fromString("1960-01-01")!!)
            },
          )
          filter(
            DateClientParam("birthdate"),
            {
              prefix = SearchComparator.Lt
              value = of(FhirDate.fromString("1990-01-01")!!)
            },
          )
        }
      },
      search("search.patient_sort_given_asc") { env ->
        env.engine.search<Patient> { sort(StringClientParam("given"), Order.ASCENDING) }
      },
      search("search.patient_sort_given_desc") { env ->
        env.engine.search<Patient> { sort(StringClientParam("given"), Order.DESCENDING) }
      },
      search("search.patient_paged") { env ->
        env.engine.search<Patient> {
          sort(StringClientParam("given"), Order.ASCENDING)
          count = 20
          from = 40
        }
      },
      search("search.patient_by_organization_reference") { env ->
        env.engine.search<Patient> {
          filter(
            ReferenceClientParam("organization"),
            { value = "Organization/${env.dataset.sampleOrganizationId}" },
          )
        }
      },
      search("search.observation_by_code") { env ->
        env.engine.search<Observation> {
          filter(
            TokenClientParam("code"),
            { value = TokenFilterValue.string(env.dataset.sampleObservationCode) },
          )
        }
      },
      search("search.observation_by_value_quantity") { env ->
        env.engine.search<Observation> {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Gt
              unit = "mg"
              value = BigDecimal.fromInt(80)
            },
          )
        }
      },
      search("search.patient_two_filters_and") { env ->
        env.engine.search<Patient> {
          filter(TokenClientParam("gender"), { value = TokenFilterValue.string("male") })
          filter(StringClientParam("given"), { value = "J" })
        }
      },
      search("search.patient_revinclude_observation") { env ->
        env.engine.search<Patient> {
          revInclude(ResourceType.Observation, ReferenceClientParam("subject"))
        }
      },
      search("search.patient_include_organization") { env ->
        env.engine.search<Patient> {
          include(ResourceType.Organization, ReferenceClientParam("organization"))
        }
      },
      search("search.patient_has_condition") { env ->
        env.engine.search<Patient> {
          has(ResourceType.Condition, ReferenceClientParam("subject")) {
            filter(TokenClientParam("code"), { value = TokenFilterValue.string("44054006") })
          }
        }
      },
      search("search.x_fhir_query_string") { env ->
        env.engine.search("Patient?gender=male&_count=50")
      },
      search("search.patient_count") { env -> env.engine.count<Patient> {} },
    )

  private fun search(id: String, block: suspend (BenchmarkEnv) -> Unit): Workload =
    SearchWorkload(id, block)

  private class SearchWorkload(
    override val id: String,
    private val block: suspend (BenchmarkEnv) -> Unit,
  ) : Workload {
    override val group = "search"
    override val opsPerIteration = QUERY_REPEATS
    override val isolation = Isolation.NONE

    override suspend fun prepare(env: BenchmarkEnv) {
      env.seedDatasetIfEmpty()
    }

    override suspend fun beforeEach(env: BenchmarkEnv) {
      env.engine.evictPageCache()
    }

    override suspend fun run(env: BenchmarkEnv) {
      repeat(QUERY_REPEATS) { block(env) }
    }
  }

  /** Repeats after the first run warm, so these are throughput rather than cold-query numbers. */
  private const val QUERY_REPEATS = 20
}
