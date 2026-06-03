/*
 * Copyright 2026 Google LLC
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

package codegen

import com.google.fhir.model.r4.Bundle
import com.google.fhir.model.r4.SearchParameter
import com.google.fhir.model.r4.terminologies.ResourceType as FhirResourceType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.WildcardTypeName
import java.io.File
import java.util.Locale
import kotlin.collections.HashMap

/**
 * Generates `SearchParameterRepository_Generated.kt`.
 *
 * The search parameter definitions live in `buildSrc/src/main/resources/search-parameters.json`,
 * which should be kept up-to-date with `http://www.hl7.org/fhir/search-parameters.json`. Replace
 * the file and re-run `./gradlew :engine:generateSearchParamsTask` (or `./gradlew build`) to
 * refresh.
 */
internal data class SearchParamDef(
  val name: String,
  val paramTypeCode: String,
  val path: String,
)

internal object SearchParameterRepositoryGenerator {

  private const val indexPackage = "dev.ohs.fhir.index"
  private const val resourcePackage = "com.google.fhir.model.r4"
  private const val generatedClassName = "SearchParameterRepository_Generated"
  private const val generatedComment =
    "This File is Generated from codegen.SearchParameterRepositoryGenerator. All changes to this " +
      "file must be made through the aforementioned file only."

  private val searchParamDefinitionClass = ClassName(indexPackage, "SearchParamDefinition")
  private val searchParamTypeClass = ClassName(indexPackage, "SearchParamType")
  private val resourceClass = ClassName(resourcePackage, "Resource")

  fun generate(bundle: Bundle, outputPath: File) {
    val searchParamMap: HashMap<String, MutableList<SearchParamDef>> = HashMap()
    val baseResourceSearchParameters = mutableListOf<SearchParamDef>()

    for (entry in bundle.entry) {
      val searchParameter = entry.resource as? SearchParameter ?: continue
      val expression = searchParameter.expression?.value
      if (expression.isNullOrEmpty()) continue

      val name = searchParameter.name.value!!
      val paramTypeCode = searchParameter.type.value!!.name.uppercase(Locale.US)
      for ((resource, path) in getResourceToPathMap(searchParameter)) {
        val def = SearchParamDef(name = name, paramTypeCode = paramTypeCode, path = path)
        searchParamMap.getOrPut(resource) { mutableListOf() }.add(def)
        if (resource == "Resource") {
          baseResourceSearchParameters.add(def)
        }
      }
    }

    val fileSpec = FileSpec.builder(indexPackage, generatedClassName)

    val getSearchParamListFunction =
      FunSpec.builder("getSearchParamList")
        .addParameter("resourceType", String::class)
        .returns(
          ClassName("kotlin.collections", "List").parameterizedBy(searchParamDefinitionClass),
        )
        .addModifiers(KModifier.INTERNAL)
        .addKdoc(generatedComment)
        .beginControlFlow("val resourceSearchParams = when (resourceType)")

    val baseParamResourceSpec = ParameterSpec.builder("resourceName", String::class).build()
    val getBaseResourceSearchParamListFunction =
      FunSpec.builder("getBaseResourceSearchParamsList")
        .addParameter(baseParamResourceSpec)
        .apply {
          addModifiers(KModifier.PRIVATE)
          returns(
            ClassName("kotlin.collections", "List").parameterizedBy(searchParamDefinitionClass),
          )
          beginControlFlow("return buildList(capacity = %L)", baseResourceSearchParameters.size)
          baseResourceSearchParameters.forEach { def ->
            addStatement(
              "add(%T(%S, %T.%L, %P))",
              searchParamDefinitionClass,
              def.name,
              searchParamTypeClass,
              def.paramTypeCode,
              "$" + "${baseParamResourceSpec.name}." + def.path.substringAfter("."),
            )
          }
          endControlFlow()
        }
        .build()
    fileSpec.addFunction(getBaseResourceSearchParamListFunction)

    searchParamMap.entries.forEach { (resource, definitions) ->
      val resourceFunction =
        FunSpec.builder("get$resource")
          .apply {
            addModifiers(KModifier.PRIVATE)
            returns(
              ClassName("kotlin.collections", "List").parameterizedBy(searchParamDefinitionClass),
            )
            beginControlFlow("return buildList(capacity = %L)", definitions.size)
            definitions.forEach { def ->
              addStatement(
                "add(%T(%S, %T.%L, %S))",
                searchParamDefinitionClass,
                def.name,
                searchParamTypeClass,
                def.paramTypeCode,
                def.path,
              )
            }
            endControlFlow()
          }
          .build()
      fileSpec.addFunction(resourceFunction)
      getSearchParamListFunction.addStatement("%S -> %L()", resource, resourceFunction.name)
    }

    getSearchParamListFunction.addStatement("else -> emptyList()").endControlFlow()
    getSearchParamListFunction.addStatement(
      "return resourceSearchParams + getBaseResourceSearchParamsList(resourceType)",
    )
    fileSpec.addFunction(getSearchParamListFunction.build())

    // Resource-name → KClass lookup. Lets `getResourceClass` work uniformly across JVM and iOS
    // without `Class.forName` (which has no Kotlin/Native equivalent).
    val kClassOutResource =
      ClassName("kotlin.reflect", "KClass")
        .parameterizedBy(WildcardTypeName.producerOf(resourceClass))
        .copy(nullable = true)
    val getResourceClassByNameFunction =
      FunSpec.builder("getResourceClassByName")
        .addParameter("name", String::class)
        .returns(kClassOutResource)
        .addModifiers(KModifier.INTERNAL)
        .addKdoc(generatedComment)
        .apply {
          beginControlFlow("return when (name)")
          FhirResourceType.values()
            .map { it.name }
            .filter { name -> runCatching { Class.forName("$resourcePackage.$name") }.isSuccess }
            .sorted()
            .forEach { name ->
              addStatement("%S -> %T::class", name, ClassName(resourcePackage, name))
            }
          addStatement("else -> null")
          endControlFlow()
        }
        .build()
    fileSpec.addFunction(getResourceClassByNameFunction)

    fileSpec.build().writeTo(outputPath)
  }

  /**
   * Splits a [SearchParameter] expression by resource type prefix.
   *
   * For example, an expression `AllergyIntolerance.code | AllergyIntolerance.reaction.substance |
   * Condition.code` becomes `{ "AllergyIntolerance" -> "AllergyIntolerance.code |
   * AllergyIntolerance.reaction.substance", "Condition" -> "Condition.code" }`.
   *
   * Necessary because spec expressions are not always grouped by resource type. See
   * https://jira.hl7.org/browse/FHIR-22724.
   */
  private fun getResourceToPathMap(searchParam: SearchParameter): Map<String, String> {
    val expression = searchParam.expression!!.value!!
    return if (searchParam.base.size == 1) {
      mapOf(searchParam.base.single().value!!.getCode() to expression)
    } else {
      expression
        .split("|")
        .groupBy { splitString -> splitString.split(".").first().trim().removePrefix("(") }
        .mapValues { it.value.joinToString(" | ") { join -> join.trim() } }
    }
  }
}
