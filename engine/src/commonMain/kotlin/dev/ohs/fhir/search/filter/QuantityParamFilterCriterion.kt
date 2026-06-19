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
import dev.ohs.fhir.search.Operation
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.search.QuantityClientParam
import dev.ohs.fhir.search.SearchDslMarker
import dev.ohs.fhir.search.getConditionParamPair
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Represents a criterion for filtering [QuantityClientParam]. e.g.
 * filter(Observation.VALUE_QUANTITY,{value = BigDecimal("5.403")} )
 */
@SearchDslMarker
data class QuantityParamFilterCriterion(
  val parameter: QuantityClientParam,
  var prefix: SearchComparator? = null,
  var value: BigDecimal? = null,
  var system: String? = null,
  var unit: String? = null,
) : FilterCriterion {

  override fun getConditionalParams(): List<ConditionParam<out Any>> {
    return listOf(getConditionParamPair(prefix, value!!, system, unit))
  }
}

internal data class QuantityParamFilterCriteria(
  val parameter: QuantityClientParam,
  override val filters: List<QuantityParamFilterCriterion>,
  override val operation: Operation,
) : FilterCriteria(filters, operation, parameter, "QuantityIndexEntity")
