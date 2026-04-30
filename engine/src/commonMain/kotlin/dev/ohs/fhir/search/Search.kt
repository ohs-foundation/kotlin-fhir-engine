/*
 * Copyright 2025-2026 Google LLC
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

package dev.ohs.fhir.search

import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.search.filter.DateClientParamFilterCriteria
import dev.ohs.fhir.search.filter.DateParamFilterCriterion
import dev.ohs.fhir.search.filter.NumberParamFilterCriteria
import dev.ohs.fhir.search.filter.NumberParamFilterCriterion
import dev.ohs.fhir.search.filter.QuantityParamFilterCriteria
import dev.ohs.fhir.search.filter.QuantityParamFilterCriterion
import dev.ohs.fhir.search.filter.ReferenceParamFilterCriteria
import dev.ohs.fhir.search.filter.ReferenceParamFilterCriterion
import dev.ohs.fhir.search.filter.StringParamFilterCriteria
import dev.ohs.fhir.search.filter.StringParamFilterCriterion
import dev.ohs.fhir.search.filter.TokenParamFilterCriteria
import dev.ohs.fhir.search.filter.TokenParamFilterCriterion
import dev.ohs.fhir.search.filter.UriFilterCriteria
import dev.ohs.fhir.search.filter.UriParamFilterCriterion


internal const val LOCAL_LAST_UPDATED = "local_lastUpdated"
internal const val LAST_UPDATED = "_lastUpdated"

/** Specifies search criteria for querying the FHIR database. */
@SearchDslMarker
class Search(
  val type: ResourceType,
  override var count: Int? = null,
  override var from: Int? = null,
) : BaseSearch {
  internal val stringFilterCriteria = mutableListOf<StringParamFilterCriteria>()
  internal val dateTimeFilterCriteria = mutableListOf<DateClientParamFilterCriteria>()
  internal val numberFilterCriteria = mutableListOf<NumberParamFilterCriteria>()
  internal val referenceFilterCriteria = mutableListOf<ReferenceParamFilterCriteria>()
  internal val tokenFilterCriteria = mutableListOf<TokenParamFilterCriteria>()
  internal val quantityFilterCriteria = mutableListOf<QuantityParamFilterCriteria>()
  internal val uriFilterCriteria = mutableListOf<UriFilterCriteria>()
  internal var sort: ClientParam? = null
  internal var order: Order? = null

  @PublishedApi internal var nestedSearches = mutableListOf<NestedSearch>()
  @PublishedApi internal var revIncludes = mutableListOf<NestedSearch>()
  @PublishedApi internal var forwardIncludes = mutableListOf<NestedSearch>()

  override var operation = Operation.AND

  override fun filter(
    stringParameter: StringClientParam,
    vararg init: StringParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<StringParamFilterCriterion>()
    init.forEach { StringParamFilterCriterion(stringParameter).apply(it).also(filters::add) }
    stringFilterCriteria.add(StringParamFilterCriteria(stringParameter, filters, operation))
  }

  override fun filter(
    referenceParameter: ReferenceClientParam,
    vararg init: ReferenceParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<ReferenceParamFilterCriterion>()
    init.forEach { ReferenceParamFilterCriterion(referenceParameter).apply(it).also(filters::add) }
    referenceFilterCriteria.add(
      ReferenceParamFilterCriteria(referenceParameter, filters, operation),
    )
  }

  override fun filter(
    dateParameter: DateClientParam,
    vararg init: DateParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<DateParamFilterCriterion>()
    init.forEach { DateParamFilterCriterion(dateParameter).apply(it).also(filters::add) }
    dateTimeFilterCriteria.add(DateClientParamFilterCriteria(dateParameter, filters, operation))
  }

  override fun filter(
    quantityParameter: QuantityClientParam,
    vararg init: QuantityParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<QuantityParamFilterCriterion>()
    init.forEach { QuantityParamFilterCriterion(quantityParameter).apply(it).also(filters::add) }
    quantityFilterCriteria.add(QuantityParamFilterCriteria(quantityParameter, filters, operation))
  }

  override fun filter(
    tokenParameter: TokenClientParam,
    vararg init: TokenParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<TokenParamFilterCriterion>()
    init.forEach { TokenParamFilterCriterion(tokenParameter).apply(it).also(filters::add) }
    tokenFilterCriteria.add(TokenParamFilterCriteria(tokenParameter, filters, operation))
  }

  override fun filter(
    numberParameter: NumberClientParam,
    vararg init: NumberParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<NumberParamFilterCriterion>()
    init.forEach { NumberParamFilterCriterion(numberParameter).apply(it).also(filters::add) }
    numberFilterCriteria.add(NumberParamFilterCriteria(numberParameter, filters, operation))
  }

  override fun filter(
    uriParam: UriClientParam,
    vararg init: UriParamFilterCriterion.() -> Unit,
    operation: Operation,
  ) {
    val filters = mutableListOf<UriParamFilterCriterion>()
    init.forEach { UriParamFilterCriterion(uriParam).apply(it).also(filters::add) }
    uriFilterCriteria.add(UriFilterCriteria(uriParam, filters, operation))
  }

  override fun sort(parameter: StringClientParam, order: Order) {
    sort = parameter
    this.order = order
  }

  override fun sort(parameter: NumberClientParam, order: Order) {
    sort = parameter
    this.order = order
  }

  override fun sort(parameter: DateClientParam, order: Order) {
    sort = parameter
    this.order = order
  }
}

enum class Order {
  ASCENDING,
  DESCENDING,
}

enum class StringFilterModifier {
  STARTS_WITH,
  MATCHES_EXACTLY,
  CONTAINS,
}

/** Logical operator between the filter values or the filters themselves. */
enum class Operation(val logicalOperator: String) {
  OR("OR"),
  AND("AND"),
}
