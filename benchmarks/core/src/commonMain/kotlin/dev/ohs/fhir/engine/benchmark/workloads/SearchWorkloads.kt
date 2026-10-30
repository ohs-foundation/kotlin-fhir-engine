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
import dev.ohs.fhir.engine.search.NumberClientParam
import dev.ohs.fhir.engine.search.Operation
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
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RiskAssessment
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType

/** Search DSL workloads, porting android-fhir's `SearchApiViewModel`. */
object SearchWorkloads {

  fun all(): List<Workload> =
    listOf(
      search("search.patient_by_given_prefix") { env ->
        env.engine.search<Patient> {
          filter(StringClientParam("given"), { value = env.dataset.sampleGivenName })
        }
      },
      search("search.patient_by_family") { env ->
        env.engine.search<Patient> {
          filter(StringClientParam("family"), { value = env.dataset.sampleFamilyName })
        }
      },
      search("search.patient_by_gender_token") { env ->
        env.engine.search<Patient> {
          filter(TokenClientParam("gender"), { value = TokenFilterValue.string("male") })
        }
      },
      search("search.patient_by_active_token") { env ->
        env.engine.search<Patient> { filter(TokenClientParam("active"), { value = of(true) }) }
      },
      // Narrow enough that a working date index makes a difference.
      search("search.patient_birthdate_range") { env ->
        env.engine.search<Patient> {
          filter(
            DateClientParam("birthdate"),
            {
              prefix = SearchComparator.Gt
              value = of(FhirDate.fromString("1974-01-01")!!)
            },
          )
          filter(
            DateClientParam("birthdate"),
            {
              prefix = SearchComparator.Lt
              value = of(FhirDate.fromString("1976-01-01")!!)
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
      search("search.patient_given_or_birthdate") { env ->
        env.engine.search<Patient> {
          // OR between two filters.
          operation = Operation.OR
          filter(StringClientParam("given"), { value = env.dataset.sampleGivenName })
          filter(
            DateClientParam("birthdate"),
            {
              prefix = SearchComparator.Lt
              value = of(FhirDate.fromString("1950-01-01")!!)
            },
          )
        }
      },
      search("search.patient_given_disjunct_values") { env ->
        env.engine.search<Patient> {
          // OR between two values of one filter.
          filter(
            StringClientParam("given"),
            { value = env.dataset.sampleGivenName },
            { value = "Jo" },
            operation = Operation.OR,
          )
        }
      },
      search("search.encounter_by_last_updated") { env ->
        // Stands in for android-fhir's `local_lastUpdated`, which this engine does not index.
        env.engine.search<Encounter> {
          sort(DateClientParam("_lastUpdated"), Order.DESCENDING)
          count = 1
        }
      },
      search(
        "search.risk_assessment_by_probability",
        seed = ::seedRiskAssessments,
      ) { env ->
        env.engine.search<RiskAssessment> {
          filter(
            NumberClientParam("probability"),
            {
              prefix = SearchComparator.Gt
              value = BigDecimal.fromInt(50)
            },
          )
        }
      },
      search(
        "search.risk_assessment_probability_or_status",
        seed = ::seedRiskAssessments,
      ) { env ->
        env.engine.search<RiskAssessment> {
          // A number filter OR'd with a filter of another type.
          operation = Operation.OR
          filter(
            NumberClientParam("probability"),
            {
              prefix = SearchComparator.Gt
              value = BigDecimal.fromInt(90)
            },
          )
          filter(
            TokenClientParam("status"),
            { value = TokenFilterValue.string("final") },
          )
        }
      },
    )

  /** The corpus has no number search parameter, so these workloads seed their own resources. */
  private suspend fun seedRiskAssessments(env: BenchmarkEnv) {
    if (env.engine.count<RiskAssessment> {} > 0L) return
    val subject = env.dataset.patientIds.firstOrNull() ?: return
    val assessments =
      (0 until RISK_ASSESSMENTS).map { index ->
        RiskAssessment(
          id = "bench-risk-assessment-$index",
          status = Enumeration(value = RiskAssessment.ObservationStatus.Final),
          subject = Reference(reference = FhirString(value = "Patient/$subject")),
          prediction =
            listOf(
              RiskAssessment.Prediction(
                probability =
                  RiskAssessment.Prediction.Probability.Decimal(
                    Decimal(value = FhirDecimal.fromInt(index % 100)),
                  ),
              ),
            ),
        )
      }
    env.engine.create(*assessments.toTypedArray())
  }

  private fun search(
    id: String,
    seed: (suspend (BenchmarkEnv) -> Unit)? = null,
    block: suspend (BenchmarkEnv) -> Unit,
  ): Workload = SearchWorkload(id, seed, block)

  private class SearchWorkload(
    override val id: String,
    /** Untimed, once per run. For queries the corpus cannot answer on its own. */
    private val seed: (suspend (BenchmarkEnv) -> Unit)?,
    private val block: suspend (BenchmarkEnv) -> Unit,
  ) : Workload {
    override val group = "search"
    override val opsPerIteration = QUERY_REPEATS
    override val isolation = Isolation.NONE

    override suspend fun prepare(env: BenchmarkEnv) {
      env.seedDatasetIfEmpty()
      seed?.invoke(env)
    }

    override suspend fun beforeEach(env: BenchmarkEnv) {
      env.engine.evictPageCache()
    }

    override suspend fun run(env: BenchmarkEnv) {
      repeat(QUERY_REPEATS) { block(env) }
    }
  }

  /** Repeats after the first run warm, so these measure throughput, not cold queries. */
  private const val QUERY_REPEATS = 20

  private const val RISK_ASSESSMENTS = 200
}
