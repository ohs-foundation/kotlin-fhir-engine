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

package dev.ohs.fhir.search

import dev.ohs.fhir.FhirEngine
import dev.ohs.fhir.SearchResult
import dev.ohs.fhir.getResourceType
import dev.ohs.fhir.search.query.XFhirQueryTranslator
import dev.ohs.fhir.model.r4.Resource

/**
 * Searches the database and returns a list of resources matching the given [Search] criteria.
 *
 * Example usage:
 * ```
 * val patients = fhirEngine.search<Patient> {
 *   filter(StringClientParam("name"), { value = "John" })
 *   count = 10
 * }
 * ```
 */
suspend inline fun <reified R : Resource> FhirEngine.search(
  init: Search.() -> Unit,
): List<SearchResult<R>> {
  val search = Search(getResourceType(R::class))
  search.init()
  return search(search)
}

/**
 * Returns the total count of entities available for the given [Search] criteria.
 *
 * Example usage:
 * ```
 * val count = fhirEngine.count<Patient> {
 *   filter(StringClientParam("name"), { value = "John" })
 * }
 * ```
 */
suspend inline fun <reified R : Resource> FhirEngine.count(
  init: Search.() -> Unit,
): Long {
  val search = Search(getResourceType(R::class))
  search.init()
  return count(search)
}

/**
 * Searches the database using a basic x-fhir-query string, e.g.
 * `Patient?gender=male&_sort=-name&_count=10`.
 *
 * Complex queries (fhirpath expressions, modifiers, prefixes, chained parameters) are not
 * supported. See [XFhirQueryTranslator].
 */
suspend fun FhirEngine.search(xFhirQuery: String): List<SearchResult<Resource>> =
  search(XFhirQueryTranslator.translate(xFhirQuery))
