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

/**
 * Checks the build-time codegen's [getSearchParamList] against the FHIR R4 search-parameters bundle
 * it consumes, one resource type at a time.
 *
 * The codegen reads `buildSrc/src/main/resources/search-parameters.json` and emits a per-resource
 * lookup. This test reads the same file at runtime, walks it with a deliberately separate parser
 * (lightweight kotlinx.serialization data classes rather than kotlin-fhir's `Bundle` model), and
 * asserts the codegen output matches what the spec says for every kotlin-fhir resource type.
 *
 * Both the codegen and the test consult the same source file, so this is not a fully independent
 * oracle the way the original android-fhir test (which read HAPI's `@SearchParamDefinition`
 * annotations) was. It still catches codegen bugs in the code-emission stage — the two paths
 * through the data are different — but it cannot catch a bug shared by both spec readers.
 */
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
            // The codegen emits base meta params (e.g. `_id`) with the path rewritten from
            // `Resource.<field>` to `<actualResource>.<field>` at call time.
            val rewrittenPath = "$name.${path.substringAfter(".")}"
            result.add(SearchParamDefinition(sp.name, type, rewrittenPath))
          }
        }
      }
    }
    return result
  }

  /**
   * Mirrors the splitting logic in
   * `codegen.SearchParameterRepositoryGenerator.getResourceToPathMap`. A SearchParameter may
   * declare multiple `base` resources with a single combined expression like
   * `AllergyIntolerance.code | Condition.code`; that needs to be split into per-resource paths.
   */
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
