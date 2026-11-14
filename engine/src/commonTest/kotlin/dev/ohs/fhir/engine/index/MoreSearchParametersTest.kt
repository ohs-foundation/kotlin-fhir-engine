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

import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Markdown
import dev.ohs.fhir.model.r4.SearchParameter
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.PublicationStatus
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.model.r4.terminologies.SearchParamType as FhirSearchParamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoreSearchParametersTest {
  @Test
  fun asMapOfResourceTypeToSearchParamDefinitions() {
    val familyNameSearchParameter =
      searchParameter(
        name = "family",
        expression = "Patient.name.family | Practitioner.name.family",
        base = listOf(ResourceType.Patient, ResourceType.Practitioner),
        type = FhirSearchParamType.String,
      )
    val genderSearchParameter =
      searchParameter(
        name = "gender",
        expression = "Patient.gender | Person.gender | Practitioner.gender | RelatedPerson.gender",
        base =
          listOf(
            ResourceType.Patient,
            ResourceType.Person,
            ResourceType.Practitioner,
            ResourceType.RelatedPerson,
          ),
        type = FhirSearchParamType.Token,
      )

    val result =
      listOf(familyNameSearchParameter, genderSearchParameter)
        .asMapOfResourceTypeToSearchParamDefinitions()

    assertEquals(
      listOf(
        SearchParamDefinition("family", SearchParamType.STRING, "Patient.name.family"),
        SearchParamDefinition("gender", SearchParamType.TOKEN, "Patient.gender"),
      ),
      result["Patient"],
    )
    assertEquals(
      listOf(
        SearchParamDefinition("family", SearchParamType.STRING, "Practitioner.name.family"),
        SearchParamDefinition("gender", SearchParamType.TOKEN, "Practitioner.gender"),
      ),
      result["Practitioner"],
    )
    assertEquals(
      listOf(SearchParamDefinition("gender", SearchParamType.TOKEN, "Person.gender")),
      result["Person"],
    )
    assertEquals(
      listOf(SearchParamDefinition("gender", SearchParamType.TOKEN, "RelatedPerson.gender")),
      result["RelatedPerson"],
    )
  }

  @Test
  fun toSearchParamDefinition() {
    val familySearchParameter =
      searchParameter(
        name = "family",
        expression = "Patient.name.family | Practitioner.name.family",
        base = listOf(ResourceType.Patient, ResourceType.Practitioner),
        type = FhirSearchParamType.String,
      )

    assertEquals(
      listOf(
        "Patient" to SearchParamDefinition("family", SearchParamType.STRING, "Patient.name.family"),
        "Practitioner" to
          SearchParamDefinition("family", SearchParamType.STRING, "Practitioner.name.family"),
      ),
      familySearchParameter.toSearchParamDefinition(),
    )
  }

  @Test
  fun toSearchParamDefinition_throws_IllegalArgumentException_when_name_is_null_or_empty() {
    for (name in listOf(null, "")) {
      val exception =
        assertFailsWith<IllegalArgumentException> {
          familySearchParameter(name = name).toSearchParamDefinition()
        }
      assertEquals("SearchParameter.name can't be null or empty.", exception.message)
    }
  }

  @Test
  fun toSearchParamDefinition_throws_IllegalArgumentException_when_expression_is_null_or_empty() {
    for (expression in listOf(null, "")) {
      val exception =
        assertFailsWith<IllegalArgumentException> {
          familySearchParameter(expression = expression).toSearchParamDefinition()
        }
      assertEquals("SearchParameter.expression can't be null or empty.", exception.message)
    }
  }

  @Test
  fun toSearchParamDefinition_throws_IllegalArgumentException_when_type_is_null() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        familySearchParameter(type = null).toSearchParamDefinition()
      }
    assertEquals("SearchParameter.type can't be null.", exception.message)
  }

  private fun familySearchParameter(
    name: String? = "family",
    expression: String? = "Patient.name.family | Practitioner.name.family",
    type: FhirSearchParamType? = FhirSearchParamType.String,
  ) =
    searchParameter(
      name = name,
      expression = expression,
      base = listOf(ResourceType.Patient, ResourceType.Practitioner),
      type = type,
    )
}

/** A [SearchParameter] with the given fields and placeholders for the other required ones. */
internal fun searchParameter(
  name: String?,
  expression: String?,
  base: List<ResourceType>,
  type: FhirSearchParamType?,
) =
  SearchParameter(
    url = Uri(value = "http://example.org/SearchParameter/$name"),
    name = FhirString(value = name),
    status = Enumeration(value = PublicationStatus.Active),
    description = Markdown(value = name),
    code = Code(value = name),
    base = base.map { Enumeration(value = it) },
    type = Enumeration(value = type),
    expression = expression?.let { FhirString(value = it) },
  )
