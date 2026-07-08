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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.FhirEngineConfiguration
import dev.ohs.fhir.FhirEngineProvider
import dev.ohs.fhir.index.SearchParamDefinition
import dev.ohs.fhir.index.SearchParamType
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.search.Order
import dev.ohs.fhir.search.Search
import dev.ohs.fhir.search.query.XFhirQueryTranslator.applyFilterParam
import dev.ohs.fhir.search.query.XFhirQueryTranslator.applySortParam
import dev.ohs.fhir.search.query.XFhirQueryTranslator.translate
import dev.ohs.fhir.testStorageDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XFhirQueryTranslatorTest {

  @BeforeTest
  fun setUp() {
    FhirEngineProvider.init(
      FhirEngineConfiguration(
        storageDirectory = testStorageDirectory(),
        customSearchParameters =
          listOf(
            SearchParamDefinition(
              name = "maritalStatus",
              type = SearchParamType.TOKEN,
              path = "Patient.maritalStatus.coding.code",
            ),
          ),
      ),
    )
    FhirEngineProvider.getInstance()
  }

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.clearInstance()
  }

  @Test
  fun translate_sortParamWithHyphen_addsDescendingSort() {
    val search = translate("Patient?_sort=-name")

    assertEquals("name", search.sort!!.paramName)
    assertEquals(Order.DESCENDING, search.order!!)
  }

  @Test
  fun translate_sortParam_addsAscendingSort() {
    val search = translate("Patient?_sort=address-country")

    assertEquals("address-country", search.sort!!.paramName)
    assertEquals(Order.ASCENDING, search.order!!)
  }

  @Test
  fun translate_sortParamMissingValue_doesNotAddSort() {
    val search = translate("Patient?_sort=")

    assertNull(search.sort)
    assertNull(search.order)
  }

  @Test
  fun translate_missingSortParam_doesNotAddSort() {
    val search = translate("Patient")

    assertNull(search.sort)
    assertNull(search.order)
  }

  @Test
  fun translate_unrecognizedSortParam_throwsIllegalArgumentException() {
    val exception =
      assertFailsWith<IllegalArgumentException> { translate("Patient?_sort=customParam") }
    assertEquals("customParam not found in Patient", exception.message)
  }

  @Test
  fun translate_countParam_addsLimit() {
    val search = translate("Patient?_count=10")

    assertEquals(10, search.count)
  }

  @Test
  fun translate_countParamMissingValue_doesNotAddLimit() {
    val search = translate("Patient?_count=")

    assertNull(search.count)
  }

  @Test
  fun translate_missingCountParam_doesNotAddLimit() {
    val search = translate("Patient")

    assertNull(search.count)
  }

  @Test
  fun translate_filterParam_addsFilters() {
    val search =
      translate("Patient?gender=male&name=John&birthdate=2012-01-11&general-practitioner=12345")

    search.stringFilterCriteria.first().run {
      assertEquals("name", this.parameter.paramName)
      assertEquals("John", this.filters.first().value)
    }

    search.tokenFilterCriteria.first().run {
      assertEquals("gender", this.parameter.paramName)
      assertEquals("male", this.filters.first().value!!.tokenFilters.first().code)
    }
    search.dateTimeFilterCriteria.first().run {
      assertEquals("birthdate", this.parameter.paramName)
      assertEquals(FhirDate.fromString("2012-01-11"), this.filters.first().value!!.date)
    }
    search.referenceFilterCriteria.first().run {
      assertEquals("general-practitioner", this.parameter.paramName)
      assertEquals("12345", this.filters.first().value)
    }
  }

  @Test
  fun translate_unrecognizedFilterParam_throwsIllegalArgumentException() {
    val exception =
      assertFailsWith<IllegalArgumentException> { translate("Patient?customParam=Abc") }
    assertEquals("customParam not found in Patient", exception.message)
  }

  @Test
  fun translate_filterParamMissingValue_doesNotAddFilters() {
    val search = translate("Patient?gender=&name=&birthdate=&general-practitioner=")

    assertTrue(search.stringFilterCriteria.isEmpty())
    assertTrue(search.tokenFilterCriteria.isEmpty())
    assertTrue(search.dateTimeFilterCriteria.isEmpty())
    assertTrue(search.referenceFilterCriteria.isEmpty())
  }

  @Test
  fun applySortParam_stringType_addsSortParam() {
    val search = Search(ResourceType.Patient)

    search.applySortParam(
      SearchParamDefinition(
        "address-country",
        SearchParamType.STRING,
        "Patient.address.country",
      ),
    )

    assertEquals("address-country", search.sort!!.paramName)
  }

  @Test
  fun applySortParam_numberType_addsSortParam() {
    val search = Search(ResourceType.RiskAssessment)

    search.applySortParam(
      SearchParamDefinition(
        "probability",
        SearchParamType.NUMBER,
        "RiskAssessment.prediction.probability",
      ),
    )

    assertEquals("probability", search.sort!!.paramName)
  }

  @Test
  fun applySortParam_dateType_addsSortParam() {
    val search = Search(ResourceType.Patient)

    search.applySortParam(
      SearchParamDefinition("birthdate", SearchParamType.DATE, "Patient.birthDate"),
    )

    assertEquals("birthdate", search.sort!!.paramName)
  }

  @Test
  fun applySortParam_unsupportedType_throwsUnsupportedOperationException() {
    val search = Search(ResourceType.Patient)

    val exception =
      assertFailsWith<UnsupportedOperationException> {
        search.applySortParam(
          SearchParamDefinition(
            "deceased",
            SearchParamType.TOKEN,
            "Patient.deceased.exists() and Patient.deceased != false",
          ),
        )
      }
    assertEquals("TOKEN sort not supported in x-fhir-query", exception.message)
  }

  @Test
  fun applyFilterParam_numberType_addsFilterParam() {
    val search = Search(ResourceType.RiskAssessment)

    search.applyFilterParam(
      SearchParamDefinition(
        "probability",
        SearchParamType.NUMBER,
        "RiskAssessment.prediction.probability",
      ),
      "12",
    )

    val applyFilterParam = search.numberFilterCriteria.first().filters.first()
    assertEquals("probability", applyFilterParam.parameter.paramName)
    assertEquals(BigDecimal.parseString("12"), applyFilterParam.value)
  }

  @Test
  fun applyFilterParam_dateType_addsFilterParam() {
    val search = Search(ResourceType.Patient)

    search.applyFilterParam(
      SearchParamDefinition("birthdate", SearchParamType.DATE, "Patient.birthDate"),
      "2022-01-21",
    )

    val applyFilterParam = search.dateTimeFilterCriteria.first().filters.first()
    assertEquals("birthdate", applyFilterParam.parameter.paramName)
    assertEquals(FhirDate.fromString("2022-01-21"), applyFilterParam.value!!.date)
  }

  @Test
  fun applyFilterParam_datetimeType_addsFilterParam() {
    val search = Search(ResourceType.Patient)

    search.applyFilterParam(
      SearchParamDefinition("birthdate", SearchParamType.DATE, "Patient.birthDate"),
      "2022-01-21T12:21:59Z",
    )

    val applyFilterParam = search.dateTimeFilterCriteria.first().filters.first()
    assertEquals("birthdate", applyFilterParam.parameter.paramName)
    assertEquals(FhirDateTime.fromString("2022-01-21T12:21:59Z"), applyFilterParam.value!!.dateTime)
  }

  @Test
  fun applyFilterParam_quantityType_addsFilterParam() {
    val search = Search(ResourceType.Encounter)

    search.applyFilterParam(
      SearchParamDefinition("length", SearchParamType.QUANTITY, "Encounter.length"),
      "3|http://unitsofmeasure.org|months",
    )

    val applyFilterParam = search.quantityFilterCriteria.first().filters.first()
    assertEquals("length", applyFilterParam.parameter.paramName)
    assertEquals(BigDecimal.parseString("3"), applyFilterParam.value)
    assertEquals("months", applyFilterParam.unit)
    assertEquals("http://unitsofmeasure.org", applyFilterParam.system)
  }

  @Test
  fun applyFilterParam_stringType_addsFilterParam() {
    val search = Search(ResourceType.Patient)

    search.applyFilterParam(
      SearchParamDefinition(
        "address-country",
        SearchParamType.STRING,
        "Patient.address.country",
      ),
      "Karachi",
    )

    val applyFilterParam = search.stringFilterCriteria.first().filters.first()
    assertEquals("address-country", applyFilterParam.parameter.paramName)
    assertEquals("Karachi", applyFilterParam.value)
  }

  @Test
  fun applyFilterParam_tokenType_addsFilterParam() {
    val search = Search(ResourceType.Patient)

    search.applyFilterParam(
      SearchParamDefinition("identifier", SearchParamType.TOKEN, "Patient.identifier"),
      "http://snomed.org|001122",
    )

    val applyFilterParam = search.tokenFilterCriteria.first().filters.first()
    assertEquals("identifier", applyFilterParam.parameter.paramName)
    assertEquals("001122", applyFilterParam.value!!.tokenFilters.first().code)
    assertEquals("http://snomed.org", applyFilterParam.value!!.tokenFilters.first().uri)
  }

  @Test
  fun applyFilterParam_referenceType_addsFilterParam() {
    val search = Search(ResourceType.Patient)

    search.applyFilterParam(
      SearchParamDefinition(
        "general-practitioner",
        SearchParamType.REFERENCE,
        "Patient.generalPractitioner",
      ),
      "Practitioner/111",
    )

    val applyFilterParam = search.referenceFilterCriteria.first().filters.first()
    assertEquals("general-practitioner", applyFilterParam.parameter.paramName)
    assertEquals("Practitioner/111", applyFilterParam.value)
  }

  @Test
  fun applyFilterParam_uriType_addsFilterParam() {
    val search = Search(ResourceType.Measure)

    search.applyFilterParam(
      SearchParamDefinition("url", SearchParamType.URI, "Measure.url"),
      "http://fhir.org/Measure/meaure-1",
    )

    val applyFilterParam = search.uriFilterCriteria.first().filters.first()
    assertEquals("url", applyFilterParam.parameter.paramName)
    assertEquals("http://fhir.org/Measure/meaure-1", applyFilterParam.value)
  }

  @Test
  fun applyFilterParam_unrecognizedType_throwsUnsupportedOperationException() {
    val search = Search(ResourceType.Location)

    val exception =
      assertFailsWith<UnsupportedOperationException> {
        search.applyFilterParam(
          SearchParamDefinition("near", SearchParamType.SPECIAL, "Location.position"),
          "20.000839 30.378273",
        )
      }
    assertEquals("SPECIAL type not supported in x-fhir-query", exception.message)
  }

  @Test
  fun translate_tagSearchParameter_addsFilter() {
    val search = translate("Location?_tag=salima-catchment")

    search.tokenFilterCriteria.first().run {
      assertEquals("_tag", this.parameter.paramName)
      assertEquals("salima-catchment", this.filters.first().value!!.tokenFilters.first().code)
    }
  }

  @Test
  fun translate_profileSearchParameter_addsFilter() {
    val search =
      translate("Patient?_profile=http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")

    search.uriFilterCriteria.first().run {
      assertEquals("_profile", this.parameter.paramName)
      assertEquals(
        "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient",
        this.filters.first().value,
      )
    }
  }

  @Test
  fun translate_customSearchParameterFromEngineConfiguration_isConsidered() {
    val search = translate("Patient?maritalStatus=M")

    search.tokenFilterCriteria.first().run {
      assertEquals("maritalStatus", this.parameter.paramName)
      assertEquals("M", this.filters.first().value!!.tokenFilters.first().code)
    }
  }
}
