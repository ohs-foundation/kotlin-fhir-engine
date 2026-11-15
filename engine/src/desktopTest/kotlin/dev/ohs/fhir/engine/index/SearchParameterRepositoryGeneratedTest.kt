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

import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Checks the generated search parameters against the `@SearchParamDefinition` annotations of the
 * HAPI R4 model classes, which android-fhir's version of this test reads by reflection.
 * `hapi-search-param-definitions.tsv` holds those annotations, so the expected values do not come
 * from the bundle the generator parses.
 */
@RunWith(Parameterized::class)
class SearchParameterRepositoryGeneratedTest(private val resourceType: ResourceType) {

  @Test
  fun getSearchParamList_matchesHapiAnnotations() {
    assertEquals(
      expectedFor(resourceType.name).sortedWith(order),
      getSearchParamList(resourceType.name).sortedWith(order),
    )
  }

  private fun expectedFor(name: String): List<SearchParamDefinition> =
    hapiDefinitions.filter { it.first == name }.map { it.second } + baseSearchParameters(name)

  // The HAPI model classes do not declare these, so they are added by hand as android-fhir does.
  private fun baseSearchParameters(name: String) =
    listOf(
      SearchParamDefinition("_id", SearchParamType.TOKEN, "$name.id"),
      SearchParamDefinition("_lastUpdated", SearchParamType.DATE, "$name.meta.lastUpdated"),
      SearchParamDefinition("_profile", SearchParamType.URI, "$name.meta.profile"),
      SearchParamDefinition("_security", SearchParamType.TOKEN, "$name.meta.security"),
      SearchParamDefinition("_source", SearchParamType.URI, "$name.meta.source"),
      SearchParamDefinition("_tag", SearchParamType.TOKEN, "$name.meta.tag"),
    )

  private companion object {
    private val order = compareBy<SearchParamDefinition>({ it.name }, { it.path })

    private val hapiDefinitions: List<Pair<String, SearchParamDefinition>> by lazy {
      val stream =
        SearchParameterRepositoryGeneratedTest::class
          .java
          .getResourceAsStream("/hapi-search-param-definitions.tsv")
          ?: error("hapi-search-param-definitions.tsv is not on the test classpath")
      stream.bufferedReader().useLines { lines ->
        lines
          .filterNot { it.startsWith("#") }
          .map { line ->
            val (type, name, paramType, path) = line.split("\t")
            type to SearchParamDefinition(name, SearchParamType.valueOf(paramType), path)
          }
          .toList()
      }
    }

    // android-fhir only instantiates concrete resources, so the abstract types are left out.
    @Parameterized.Parameters(name = "{0}")
    @JvmStatic
    fun data(): List<ResourceType> =
      ResourceType.entries - ResourceType.Resource - ResourceType.DomainResource
  }
}
