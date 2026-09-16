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
package dev.ohs.fhir.engine.search.filter

import dev.ohs.fhir.engine.search.ConditionParam
import dev.ohs.fhir.engine.search.Operation
import dev.ohs.fhir.engine.search.SearchDslMarker
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.StringFilterModifier

/**
 * Represents a criterion for filtering [StringClientParam]. e.g. filter(Patient.FAMILY, { value =
 * "Jones" })
 */
@SearchDslMarker
data class StringParamFilterCriterion(
  val parameter: StringClientParam,
  var modifier: StringFilterModifier = StringFilterModifier.STARTS_WITH,
  var value: String? = null,
) : FilterCriterion {

  override fun getConditionalParams(): List<ConditionParam<out Any>> {
    return listOf(
      when (modifier) {
        // The wildcard is appended to the bound argument rather than concatenated in SQL. SQLite
        // only applies its LIKE optimisation when the pattern is a literal or a plain parameter,
        // so `LIKE ? || '%'` was an expression the planner could not see into and every prefix
        // search scanned. `index_value` is NOCASE, which the optimisation also requires, and LIKE
        // is case-insensitive for ASCII regardless — so dropping the explicit COLLATE changes
        // which index is used, not which rows match.
        StringFilterModifier.STARTS_WITH -> ConditionParam("index_value LIKE ?", "${value!!}%")
        // FHIR's `:exact` is case- and accent-sensitive, so it must not inherit the column's
        // NOCASE collation. The explicit BINARY keeps the semantics and costs the index: a NOCASE
        // index cannot serve a BINARY comparison, so `:exact` narrows on
        // `(resourceType, index_name)`. Indexing both would need a second, BINARY column.
        StringFilterModifier.MATCHES_EXACTLY ->
          ConditionParam("index_value = ? COLLATE BINARY", value!!)
        // A leading wildcard can never use an index, whatever the collation.
        StringFilterModifier.CONTAINS -> ConditionParam("index_value LIKE ?", "%${value!!}%")
      },
    )
  }
}

internal data class StringParamFilterCriteria(
  val parameter: StringClientParam,
  override val filters: List<StringParamFilterCriterion>,
  override val operation: Operation,
) : FilterCriteria(filters, operation, parameter, "StringIndexEntity")
