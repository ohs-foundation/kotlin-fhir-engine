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
package dev.ohs.fhir.index

import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SearchParameterRepositoryGeneratedTest(private val resourceType: ResourceType) {

  @Test
  fun getSearchParamList_matchesFhirR4Spec() {
    assertEquals(expectedFor(resourceType.name), getSearchParamList(resourceType.name).toSet())
  }

  private fun expectedFor(name: String): Set<SearchParamDefinition> {
    val result = mutableSetOf<SearchParamDefinition>()
    for (entry in spec.entry) {
      val sp = entry.resource
      val expression = sp.expression ?: continue
      if (expression.isEmpty()) continue
      val type = SearchParamType.valueOf(sp.type.uppercase())
      for ((base, path) in resourceToPathMap(sp)) {
        when (base) {
          name -> result.add(SearchParamDefinition(sp.name, type, path))
          "Resource" -> {
            val rewrittenPath = "$name.${path.substringAfter(".")}"
            result.add(SearchParamDefinition(sp.name, type, rewrittenPath))
          }
        }
      }
    }
    return result
  }

  private fun resourceToPathMap(sp: SpecSearchParameter): Map<String, String> {
    val expression = sp.expression!!
    return if (sp.base.size == 1) {
      mapOf(sp.base.single() to expression)
    } else {
      expression
        .split("|")
        .groupBy { it.split(".").first().trim().removePrefix("(") }
        .mapValues { entry -> entry.value.joinToString(" | ") { it.trim() } }
    }
  }

  @Serializable private data class SpecBundle(val entry: List<SpecEntry>)

  @Serializable private data class SpecEntry(val resource: SpecSearchParameter)

  @Serializable
  private data class SpecSearchParameter(
    val name: String,
    val base: List<String>,
    val type: String,
    val expression: String? = null,
  )

  private companion object {
    private val json = Json { ignoreUnknownKeys = true }

    private val spec: SpecBundle by lazy {
      val stream =
        SearchParameterRepositoryGeneratedTest::class
          .java
          .getResourceAsStream("/search-parameters.json")
          ?: error(
            "search-parameters.json is not on the test classpath — check the resources.srcDir wiring in engine/build.gradle.kts"
          )
      stream.bufferedReader().use { json.decodeFromString(SpecBundle.serializer(), it.readText()) }
    }

    @Parameterized.Parameters(name = "{0}")
    @JvmStatic
    fun data(): List<ResourceType> = ResourceType.entries
  }
}
