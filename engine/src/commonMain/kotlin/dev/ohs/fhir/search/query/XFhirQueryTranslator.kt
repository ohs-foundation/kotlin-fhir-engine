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

package dev.ohs.fhir.search.query

import dev.ohs.fhir.FhirEngineProvider
import dev.ohs.fhir.index.SearchParamDefinition
import dev.ohs.fhir.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.index.SearchParamType
import dev.ohs.fhir.isValidDateOnly
import dev.ohs.fhir.search.DateClientParam
import dev.ohs.fhir.search.NumberClientParam
import dev.ohs.fhir.search.Order
import dev.ohs.fhir.search.QuantityClientParam
import dev.ohs.fhir.search.ReferenceClientParam
import dev.ohs.fhir.search.Search
import dev.ohs.fhir.search.StringClientParam
import dev.ohs.fhir.search.TokenClientParam
import dev.ohs.fhir.search.UriClientParam
import dev.ohs.fhir.search.filter.TokenFilterValue
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Supports translation of x-fhir-query defined in
 * http://build.fhir.org/ig/HL7/sdc/expressions.html#x-fhir-query-enhancements and
 * http://hl7.org/fhir/R4/search.html
 */
internal object XFhirQueryTranslator {
  private const val XFHIR_QUERY_SORT_PARAM = "_sort"
  private const val XFHIR_QUERY_COUNT_PARAM = "_count"

  // NOTE: This is a lazy accessor (not an eager `val` like the engine) because the engine relies on
  // Robolectric's per-test classloader isolation to re-evaluate the provider for each test. KMP
  // test
  // runs share a single JVM/object instance, so an eager `val` would capture a stale provider
  // across
  // test classes (e.g. one without custom search parameters). Re-reading per call matches the
  // engine's effective per-test behavior.
  private val searchParamProvider: SearchParamDefinitionsProviderImpl
    get() = FhirEngineProvider.getSearchParamProvider() ?: SearchParamDefinitionsProviderImpl()

  /**
   * Translates the basic x-fhir-query string defined in
   * http://build.fhir.org/ig/HL7/sdc/expressions.html#x-fhir-query-enhancements and
   * http://hl7.org/fhir/R4/search.html
   *
   * Example: Patient?active=true&gender=male&_sort=-name,gender&_count=11
   *
   * Complex queries including fhirpath expressions, global common search params, modifiers,
   * prefixes, chained parameters are not supported.
   */
  internal fun translate(xFhirQuery: String): Search {
    val (type, queryStringPairs) =
      xFhirQuery.split("?").let {
        ResourceType.fromCode(it.first()) to it.elementAtOrNull(1)?.split("&")
      }
    val queryParams =
      queryStringPairs?.mapNotNull {
        // skip missing values like active=[missing]
        it
          .split("=")
          .takeIf { it.size > 1 && it[1].isNotBlank() }
          ?.let { it.first() to it.elementAt(1) }
      }

    val sort =
      queryParams
        ?.find { it.first == XFHIR_QUERY_SORT_PARAM }
        ?.second
        ?.takeIf { it.isNotBlank() }
        ?.split(",")
        ?.map {
          // sortParam with prefix '-' means descending order
          it.removePrefix("-") to if (it.startsWith("-")) Order.DESCENDING else Order.ASCENDING
        }
    val count =
      queryParams
        ?.find { it.first == XFHIR_QUERY_COUNT_PARAM }
        ?.second
        ?.takeIf { it.isNotBlank() }
        ?.toInt()
    val searchParams =
      queryParams
        ?.filter {
          listOf(XFHIR_QUERY_COUNT_PARAM, XFHIR_QUERY_SORT_PARAM).contains(it.first).not()
        }
        ?.toMap()

    val querySearchParameters = searchParams?.toSearchParamDefinitionValueMap(type)

    return Search(type, count).apply {
      querySearchParameters?.forEach {
        val (param, filterValue) = it
        this.applyFilterParam(param, filterValue)
      }

      sort?.forEach { sortParam ->
        sortParam.first.toSearchParamDefinition(type).let { sort ->
          this.applySortParam(sort, sortParam.second)
        }
      }
    }
  }

  fun Search.applyFilterParam(param: SearchParamDefinition, filterValue: String) =
    when (param.type) {
      SearchParamType.NUMBER -> {
        this.filter(NumberClientParam(param.name), { value = BigDecimal.parseString(filterValue) })
      }
      SearchParamType.DATE -> {
        if (!isValidDateOnly(filterValue)) {
          val dateTime =
            requireNotNull(FhirDateTime.fromString(filterValue)) {
              "Invalid x-fhir-query dateTime value: $filterValue"
            }
          this.filter(DateClientParam(param.name), { value = of(dateTime) })
        } else {
          val date =
            requireNotNull(FhirDate.fromString(filterValue)) {
              "Invalid x-fhir-query date value: $filterValue"
            }
          this.filter(DateClientParam(param.name), { value = of(date) })
        }
      }
      SearchParamType.QUANTITY -> {
        filterValue.toQuantity().let { quantity ->
          this.filter(
            QuantityClientParam(param.name),
            {
              value = quantity.value
              system = quantity.system
              unit = quantity.unit
            },
          )
        }
      }
      SearchParamType.STRING -> {
        this.filter(StringClientParam(param.name), { value = filterValue })
      }
      SearchParamType.TOKEN -> {
        filterValue.toCoding().let { coding ->
          this.filter(
            TokenClientParam(param.name),
            { value = TokenFilterValue.coding(coding.system, coding.code) },
          )
        }
      }
      SearchParamType.REFERENCE -> {
        this.filter(ReferenceClientParam(param.name), { value = filterValue })
      }
      SearchParamType.URI -> {
        this.filter(UriClientParam(param.name), { value = filterValue })
      }
      else ->
        throw UnsupportedOperationException("${param.type} type not supported in x-fhir-query")
    }

  internal fun Search.applySortParam(param: SearchParamDefinition, order: Order = Order.ASCENDING) =
    when (param.type) {
      SearchParamType.NUMBER -> {
        this.sort(NumberClientParam(param.name), order)
      }
      SearchParamType.DATE -> {
        this.sort(DateClientParam(param.name), order)
      }
      SearchParamType.STRING -> {
        this.sort(StringClientParam(param.name), order)
      }
      else ->
        throw UnsupportedOperationException("${param.type} sort not supported in x-fhir-query")
    }

  private val ResourceType.resourceSearchParameters: List<SearchParamDefinition>
    get() = searchParamProvider.getByResourceType(this.name)

  /** Parse string key-val map to SearchParamDefinition-Value map */
  private fun Map<String, String>.toSearchParamDefinitionValueMap(
    type: ResourceType,
  ): List<Pair<SearchParamDefinition, String>> {
    return this.map { (paramKey, paramValue) ->
      val paramDefinition = paramKey.toSearchParamDefinition(type)
      Pair(paramDefinition, paramValue)
    }
  }

  /** Parse param to SearchParamDefinition for given resourceType */
  private fun String.toSearchParamDefinition(resourceType: ResourceType) =
    resourceType.resourceSearchParameters.find { it.name == this }
      ?: throw IllegalArgumentException("$this not found in ${resourceType.name}")

  /**
   * Parse quantity string as defined in specs https://hl7.org/fhir/search.html#quantity
   *
   * Components: value|system|unit OR value|unit OR value
   *
   * Examples: 5.4|http://unitsofmeasure.org|mg OR 5.4|mg OR 5.4
   */
  private fun String.toQuantity(): ParsedQuantity =
    this.split("|").let { parts ->
      ParsedQuantity(
        value = BigDecimal.parseString(parts.first()),
        // system exists at index 1 only if all 3 components are specified
        system = if (parts.size == 3) parts.elementAt(1) else null,
        // unit exists as last element only for two or more components
        unit = if (parts.size > 1) parts.last() else null,
      )
    }

  /**
   * Parse coding string as defined in specs https://hl7.org/fhir/search.html#token
   *
   * Components: system|code OR code
   *
   * Examples: http://snomed.org|112233 OR 112233
   */
  private fun String.toCoding(): ParsedCoding =
    this.split("|").let { parts ->
      ParsedCoding(
        // system exists as first element only if both components are specified
        system = if (parts.size == 2) parts.first() else null,
        // code would always be specified and would exist as last element
        code = parts.last(),
      )
    }

  private data class ParsedQuantity(
    val value: BigDecimal,
    val system: String?,
    val unit: String?,
  )

  private data class ParsedCoding(val system: String?, val code: String)
}
