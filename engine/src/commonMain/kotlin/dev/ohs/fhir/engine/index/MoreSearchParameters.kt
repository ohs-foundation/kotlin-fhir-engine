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
package dev.ohs.fhir.engine.index

import dev.ohs.fhir.model.r4.SearchParameter
import dev.ohs.fhir.model.r4.terminologies.SearchParamType as FhirSearchParamType

/** @return the [SearchParamDefinition]s of these search parameters, by resource type name. */
internal fun List<SearchParameter>.asMapOfResourceTypeToSearchParamDefinitions():
  Map<String, List<SearchParamDefinition>> =
  flatMap { it.toSearchParamDefinition() }.groupBy({ it.first }, { it.second })

/** @return pairs of a resource type name and its [SearchParamDefinition]. */
internal fun SearchParameter.toSearchParamDefinition(): List<Pair<String, SearchParamDefinition>> {
  val name = name.value
  require(!name.isNullOrEmpty()) { "SearchParameter.name can't be null or empty." }

  val type = requireNotNull(type.value) { "SearchParameter.type can't be null." }

  val expression = expression?.value
  require(!expression.isNullOrEmpty()) { "SearchParameter.expression can't be null or empty." }

  return getResourceToPathMap(expression).map { (resourceType, path) ->
    resourceType to SearchParamDefinition(name = name, type = type.toSearchParamType(), path = path)
  }
}

private fun SearchParameter.getResourceToPathMap(expression: String): Map<String, String> {
  // The single base case is there because of https://jira.hl7.org/browse/FHIR-22724, as in
  // android-fhir.
  val singleBase = base.singleOrNull()?.value
  return if (singleBase != null) {
    mapOf(singleBase.code to expression)
  } else {
    expression
      .split("|")
      .groupBy { it.split(".").first().trim().removePrefix("(") }
      .mapValues { it.value.joinToString(" | ") { path -> path.trim() } }
  }
}

private fun FhirSearchParamType.toSearchParamType(): SearchParamType =
  when (this) {
    FhirSearchParamType.Number -> SearchParamType.NUMBER
    FhirSearchParamType.Date -> SearchParamType.DATE
    FhirSearchParamType.String -> SearchParamType.STRING
    FhirSearchParamType.Token -> SearchParamType.TOKEN
    FhirSearchParamType.Reference -> SearchParamType.REFERENCE
    FhirSearchParamType.Composite -> SearchParamType.COMPOSITE
    FhirSearchParamType.Quantity -> SearchParamType.QUANTITY
    FhirSearchParamType.Uri -> SearchParamType.URI
    FhirSearchParamType.Special -> SearchParamType.SPECIAL
  }
