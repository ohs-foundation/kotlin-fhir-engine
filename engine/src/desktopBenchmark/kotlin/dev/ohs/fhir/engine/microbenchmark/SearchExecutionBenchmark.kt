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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.search.NumberClientParam
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.StringFilterModifier
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.RiskAssessment
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * Measures a search through the engine, from query to parsed resources, for each filter kind except
 * `near` and date.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class SearchExecutionBenchmark {

  @Param("1000", "5000") var rows: Int = 0

  private lateinit var database: EngineBenchmarkDatabase

  private lateinit var stringPrefix: Search
  private lateinit var stringExact: Search
  private lateinit var stringContains: Search
  private lateinit var token: Search
  private lateinit var number: Search
  private lateinit var reference: Search
  private lateinit var include: Search
  private lateinit var revInclude: Search
  private lateinit var firstPage: Search
  private lateinit var counted: Search

  @Setup
  fun setUp() {
    val subjects = rows / SUBJECT_SHARE
    val riskRows = rows / RISK_SHARE
    database = EngineBenchmarkDatabase.create("search-$rows")
    database.seedPatients(rows)
    database.seedObservations(rows, subjects)
    database.seedRiskAssessments(riskRows)

    val namePrefix = IndexBenchmarkDatabase.prefixFor(IndexBenchmarkDatabase.PROBE_ROW)
    val family = EngineBenchmarkDatabase.familyName(IndexBenchmarkDatabase.PROBE_ROW)
    val code = EngineBenchmarkDatabase.CODES[IndexBenchmarkDatabase.PROBE_ROW % CODE_COUNT]

    stringPrefix = patientSearch { filter(StringClientParam(FAMILY), { value = namePrefix }) }
    stringExact = patientSearch {
      filter(
        StringClientParam(FAMILY),
        {
          value = family
          modifier = StringFilterModifier.MATCHES_EXACTLY
        },
      )
    }
    stringContains = patientSearch {
      filter(
        StringClientParam(FAMILY),
        {
          value = family
          modifier = StringFilterModifier.CONTAINS
        },
      )
    }
    token = observationSearch { filter(TokenClientParam(CODE), { value = of(code) }) }
    number =
      Search(type = ResourceType.RiskAssessment).apply {
        filter(
          NumberClientParam(PROBABILITY),
          {
            value = BigDecimal.fromInt(RISK_THRESHOLD)
            prefix = SearchComparator.Gt
          },
        )
      }
    reference = observationSearch {
      filter(
        ReferenceClientParam(SUBJECT),
        { value = "Patient/${EngineBenchmarkDatabase.patientId(0)}" },
      )
    }
    include = observationSearch {
      filter(TokenClientParam(CODE), { value = of(code) })
      include<Patient>(ReferenceClientParam(SUBJECT))
    }
    revInclude = patientSearch {
      filter(StringClientParam(FAMILY), { value = namePrefix })
      revInclude<Observation>(ReferenceClientParam(SUBJECT))
    }
    firstPage = Search(type = ResourceType.Patient, count = PAGE, from = 0)
    counted = patientSearch { filter(StringClientParam(FAMILY), { value = namePrefix }) }

    assertSelectivity(
      database.search<Patient>(stringPrefix).size,
      rows,
      1.0 / IndexBenchmarkDatabase.PREFIX_COMBINATIONS,
      "stringPrefix",
    )
    check(database.search<Patient>(stringExact).size == 1) {
      "the exact search matched ${database.search<Patient>(stringExact).size} patients, expected 1"
    }
    // The family name is also part of longer names, so the count varies with the corpus.
    check(database.search<Patient>(stringContains).isNotEmpty()) {
      "the contains search matched nothing, so it is timing a scan over no result"
    }
    assertSelectivity(
      database.search<Observation>(token).size,
      rows,
      1.0 / CODE_COUNT,
      "token",
    )
    assertSelectivity(
      database.search<RiskAssessment>(number).size,
      riskRows,
      (EngineBenchmarkDatabase.MAX_RISK - RISK_THRESHOLD - 1.0) / EngineBenchmarkDatabase.MAX_RISK,
      "number",
    )
    check(database.search<Observation>(reference).size == SUBJECT_SHARE) {
      "one patient should carry $SUBJECT_SHARE observations, found " +
        database.search<Observation>(reference).size
    }
    check(database.search<Observation>(include).any { !it.included.isNullOrEmpty() }) {
      "no result carried an included patient, so includeSearch measures the plain token filter"
    }
    check(database.search<Patient>(revInclude).any { !it.revIncluded.isNullOrEmpty() }) {
      "no result carried a revIncluded observation, so revIncludeSearch measures the plain filter"
    }
    check(database.search<Patient>(firstPage).size == PAGE) {
      "a first page returned ${database.search<Patient>(firstPage).size} patients, expected $PAGE"
    }
  }

  @TearDown fun tearDown() = database.close()

  /** A prefix match on one slice of the patients. */
  @Benchmark fun stringPrefixSearch(): Int = database.search<Patient>(stringPrefix).size

  /** An indexed equality that matches one row. */
  @Benchmark fun stringExactSearch(): Int = database.search<Patient>(stringExact).size

  /** `:contains` cannot use the index. */
  @Benchmark fun stringContainsSearch(): Int = database.search<Patient>(stringContains).size

  /** The token index is the only one that is already covering. */
  @Benchmark fun tokenSearch(): Int = database.search<Observation>(token).size

  /** A range over the number index. */
  @Benchmark fun numberSearch(): Int = database.search<RiskAssessment>(number).size

  /** The lookup that chained, `has` and `revInclude` searches use. */
  @Benchmark fun referenceSearch(): Int = database.search<Observation>(reference).size

  /** A second query for the referenced patients, grouped per matched observation. */
  @Benchmark fun includeSearch(): Int = database.search<Observation>(include).size

  /** Every observation that references a matched patient. */
  @Benchmark fun revIncludeSearch(): Int = database.search<Patient>(revInclude).size

  /** One page of patients with no filter. */
  @Benchmark fun firstPageSearch(): Int = database.search<Patient>(firstPage).size

  /** The prefix filter counted, so no payload is read or parsed. */
  @Benchmark fun countMatching(): Long = database.count(counted)

  private fun patientSearch(init: Search.() -> Unit): Search =
    Search(type = ResourceType.Patient).apply(init)

  private fun observationSearch(init: Search.() -> Unit): Search =
    Search(type = ResourceType.Observation).apply(init)

  private companion object {
    const val FAMILY = "family"
    const val CODE = "code"
    const val SUBJECT = "subject"
    const val PROBABILITY = "probability"

    /** Observations per referenced patient. */
    const val SUBJECT_SHARE = 4

    /** One risk assessment per this many patients. */
    const val RISK_SHARE = 4

    /** Matches about nine in a hundred seeded probabilities. */
    const val RISK_THRESHOLD = 90

    const val PAGE = 50
    val CODE_COUNT = EngineBenchmarkDatabase.CODES.size
  }
}
