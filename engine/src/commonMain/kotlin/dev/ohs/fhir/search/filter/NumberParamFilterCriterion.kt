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

package dev.ohs.fhir.search.filter


import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.search.ConditionParam
import dev.ohs.fhir.search.NumberClientParam
import dev.ohs.fhir.search.Operation
import dev.ohs.fhir.search.ParamPrefixEnum
import dev.ohs.fhir.search.getConditionParamPair

data class NumberParamFilterCriterion(
  val parameter: NumberClientParam,
  var prefix: ParamPrefixEnum? = null,
  var value: BigDecimal? = null,
) : FilterCriterion {

  override fun getConditionalParams(): List<ConditionParam<out Any>> {
    return listOf(getConditionParamPair(prefix, value!!))
  }
}

internal data class NumberParamFilterCriteria(
  val parameter: NumberClientParam,
  override val filters: List<NumberParamFilterCriterion>,
  override val operation: Operation,
) : FilterCriteria(filters, operation, parameter, "NumberIndexEntity")
