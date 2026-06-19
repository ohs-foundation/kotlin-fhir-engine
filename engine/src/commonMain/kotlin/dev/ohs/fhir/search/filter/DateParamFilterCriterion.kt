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

package dev.ohs.fhir.search.filter

import dev.ohs.fhir.search.ConditionParam
import dev.ohs.fhir.search.DateClientParam
import dev.ohs.fhir.search.Operation
import dev.ohs.fhir.search.ParamPrefixEnum
import dev.ohs.fhir.search.SearchDslMarker
import dev.ohs.fhir.search.SearchQuery
import dev.ohs.fhir.search.getConditionParamPairForDate
import dev.ohs.fhir.search.getConditionParamPairForDateTime
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.terminologies.ResourceType

/**
 * Represents a criterion for filtering [DateClientParam]. e.g. filter(Patient.BIRTHDATE, { value
 * =of(FhirDate.fromString("2013-03-14")) })
 */
@SearchDslMarker
data class DateParamFilterCriterion(
  val parameter: DateClientParam,
  var prefix: ParamPrefixEnum = ParamPrefixEnum.EQUAL,
  var value: DateFilterValues? = null,
) : FilterCriterion {
  /** Returns [DateFilterValues] from [FhirDate]. */
  fun of(date: FhirDate) = DateFilterValues().apply { this.date = date }

  /** Returns [DateFilterValues] from [FhirDateTime]. */
  fun of(dateTime: FhirDateTime) = DateFilterValues().apply { this.dateTime = dateTime }

  override fun getConditionalParams(): List<ConditionParam<Long>> {
    checkNotNull(value) { "DateClientParamFilter.value can't be null." }
    return when {
      value!!.date != null -> listOf(getConditionParamPairForDate(prefix, value!!.date!!))
      value!!.dateTime != null ->
        listOf(getConditionParamPairForDateTime(prefix, value!!.dateTime!!))
      else -> {
        throw IllegalStateException(
          "DateClientParamFilter.value should have either FhirDate or FhirDateTime.",
        )
      }
    }
  }
}

@SearchDslMarker
class DateFilterValues internal constructor() {
  var date: FhirDate? = null
  var dateTime: FhirDateTime? = null
}

/**
 * It implements its own [query] function as [DateClientParamFilterCriteria] can have both
 * [FhirDate] and [FhirDateTime] criterion in the same filter and both of those values are stored in
 * different entity tables.
 */
internal data class DateClientParamFilterCriteria(
  val parameter: DateClientParam,
  override val filters: List<DateParamFilterCriterion>,
  override val operation: Operation,
) : FilterCriteria(filters, operation, parameter, "") {

  /**
   * @return a unified DateClientParam [SearchQuery] that joins the individual Date and DateTime
   *   queries. A filter may carry only one of the two criterion types, so [FilterCriteria] with no
   *   criterion are filtered out before joining.
   */
  override fun query(type: ResourceType): SearchQuery {
    val filterCriteria =
      listOf(
        DateFilterCriteria(parameter, filters.filter { it.value!!.date != null }, operation),
        DateTimeFilterCriteria(
          parameter,
          filters.filter { it.value!!.dateTime != null },
          operation,
        ),
      )

    return filterCriteria
      .filter { it.filters.isNotEmpty() }
      .map { it.query(type) }
      .let { queries ->
        SearchQuery(
          queries.joinToString(separator = " ${operation.logicalOperator} ") {
            if (queries.size > 1) "(${it.query})" else it.query
          },
          queries.flatMap { it.args },
        )
      }
  }

  /** Internal class used to generate query for Date type Criterion */
  private data class DateFilterCriteria(
    val parameter: DateClientParam,
    override val filters: List<DateParamFilterCriterion>,
    override val operation: Operation,
  ) : FilterCriteria(filters, operation, parameter, "DateIndexEntity")

  /** Internal class used to generate query for DateTime type Criterion */
  private data class DateTimeFilterCriteria(
    val parameter: DateClientParam,
    override val filters: List<DateParamFilterCriterion>,
    override val operation: Operation,
  ) : FilterCriteria(filters, operation, parameter, "DateTimeIndexEntity")
}
