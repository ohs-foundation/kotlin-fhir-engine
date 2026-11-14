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
package dev.ohs.fhir.engine.search

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.search.filter.ReferenceParamFilterCriterion
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class SearchTest {

  @Test
  fun search() {
    val query = Search(ResourceType.Patient).getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceType = ?
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf(ResourceType.Patient.name), query.args)
  }

  @Test
  fun count() {
    val query = Search(ResourceType.Patient).getQuery(true)

    assertEquals(
      """
      SELECT COUNT(*)
      FROM ResourceEntity a
      WHERE a.resourceType = ?
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf(ResourceType.Patient.name), query.args)
  }

  @Test
  fun search_size() {
    val query = Search(ResourceType.Patient).apply { count = 10 }.getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceType = ?
      LIMIT ?
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf(ResourceType.Patient.name, 10), query.args)
  }

  @Test
  fun search_size_from() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          count = 10
          from = 20
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceType = ?
      LIMIT ? OFFSET ?
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf(ResourceType.Patient.name, 10, 20), query.args)
  }

  @Test
  fun search_filter_string_default() {
    val query =
      Search(ResourceType.Patient)
        .apply { filter(StringClientParam("address"), { value = "someValue" }) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "address", "someValue%"),
      query.args,
    )
  }

  @Test
  fun search_filter_string_exact() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("address"),
            {
              modifier = StringFilterModifier.MATCHES_EXACTLY
              value = "someValue"
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND index_value = ? COLLATE BINARY)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "address", "someValue", "someValue"),
      query.args,
    )
  }

  @Test
  fun search_filter_string_contains() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("address"),
            {
              modifier = StringFilterModifier.CONTAINS
              value = "someValue"
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE '%' || ? || '%' COLLATE NOCASE
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "address", "someValue"),
      query.args,
    )
  }

  @Test
  fun search_filter_token_coding() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("gender"),
            {
              value =
                of(coding("http://hl7.org/fhir/ValueSet/administrative-gender", "male", "Male"))
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "gender",
        "male",
        "http://hl7.org/fhir/ValueSet/administrative-gender",
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_token_codeableConcept() {
    val query =
      Search(ResourceType.Immunization)
        .apply {
          filter(
            TokenClientParam("vaccine-code"),
            {
              value =
                of(
                  CodeableConcept(
                    coding = listOf(coding("http://snomed.info/sct", "260385009", "Allergy X")),
                  ),
                )
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Immunization.name,
        "vaccine-code",
        "260385009",
        "http://snomed.info/sct",
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_token_codeableConcept_withTwoCodings() {
    val query =
      Search(ResourceType.Immunization)
        .apply {
          filter(
            TokenClientParam("vaccine-code"),
            {
              value =
                of(
                  CodeableConcept(
                    coding =
                      listOf(
                        coding("http://snomed.info/sct", "260385009", "Allergy X"),
                        coding("http://hl7.org/fhir/sid/cvx", "140", "Influenza"),
                      ),
                  ),
                )
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND ((index_value = ? AND IFNULL(index_system,'') = ?) OR (index_value = ? AND IFNULL(index_system,'') = ?))
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Immunization.name,
        "vaccine-code",
        "260385009",
        "http://snomed.info/sct",
        "140",
        "http://hl7.org/fhir/sid/cvx",
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_token_identifier() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("identifier"),
            {
              value =
                of(
                  Identifier(
                    system = Uri(value = "http://acme.org/patient"),
                    value = FhirString(value = "12345"),
                  ),
                )
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "identifier",
        "12345",
        "http://acme.org/patient",
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_token_codeType() {
    val query =
      Search(ResourceType.Patient)
        .apply { filter(TokenClientParam("gender"), { value = of(Code(value = "male")) }) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "gender", "male"),
      query.args,
    )
  }

  @Test
  fun search_filter_token_boolean() {
    val query =
      Search(ResourceType.Patient)
        .apply { filter(TokenClientParam("active"), { value = of(true) }) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "active", "true"),
      query.args,
    )
  }

  @Test
  fun search_filter_token_uriType() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("identifier"),
            { value = of(Uri(value = "16009886-bd57-11eb-8529-0242ac130003")) },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "identifier",
        "16009886-bd57-11eb-8529-0242ac130003",
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_token_string() {
    val query =
      Search(ResourceType.Patient)
        .apply { filter(TokenClientParam("phone"), { value = of("+14845219791") }) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "phone", "+14845219791"),
      query.args,
    )
  }

  @Test
  fun search_filter_token_contactPoint() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("telecom"),
            {
              value =
                of(
                  ContactPoint(
                    system = Enumeration(value = ContactPoint.ContactPointSystem.Email),
                    use = Enumeration(value = ContactPoint.ContactPointUse.Home),
                    value = FhirString(value = "test@gmail.com"),
                  ),
                )
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "telecom",
        "test@gmail.com",
        ContactPoint.ContactPointUse.Home.code,
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_token_contactPoint_missingUse() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("telecom"),
            {
              value =
                of(
                  ContactPoint(
                    system = Enumeration(value = ContactPoint.ContactPointSystem.Email),
                    value = FhirString(value = "test@gmail.com"),
                  ),
                )
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "telecom", "test@gmail.com"),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_equals() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Eq
              unit = "g"
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_code = ? AND index_value >= ? AND index_value < ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        "g",
        BigDecimal.parseString("5.4025").doubleValue(false),
        BigDecimal.parseString("5.4035").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_less() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Lt
              unit = "g"
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_code = ? AND index_value < ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        "g",
        BigDecimal.parseString("5.403").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_greater() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Gt
              system = "http://unitsofmeasure.org"
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_system = ? AND index_value > ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        "http://unitsofmeasure.org",
        BigDecimal.parseString("5.403").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_canonical_match() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Eq
              value = BigDecimal.parseString("5403")
              system = "http://unitsofmeasure.org"
              unit = "mg"
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_system = ? AND index_code = ? AND index_value >= ? AND index_value < ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    // kotlin-fhir-path canonicalizes mg to g1, android-fhir's UCUM service gives g.
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        "http://unitsofmeasure.org",
        "g1",
        5.4025,
        5.4035,
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_uri() {
    val query =
      Search(ResourceType.Library)
        .apply { filter(UriClientParam("url"), { value = "someValue" }) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM UriIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Library.name, "url", "someValue"),
      query.args,
    )
  }

  @Test
  fun search_sort_string_ascending() {
    val query =
      Search(ResourceType.Patient)
        .apply { sort(StringClientParam("given"), Order.ASCENDING) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      LEFT JOIN StringIndexEntity b
      ON a.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE a.resourceType = ?
      GROUP BY a.resourceUuid
      HAVING MIN(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, x'') ASC
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("given", ResourceType.Patient.name), query.args)
  }

  @Test
  fun search_sort_string_descending() {
    val query =
      Search(ResourceType.Patient)
        .apply { sort(StringClientParam("given"), Order.DESCENDING) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      LEFT JOIN StringIndexEntity b
      ON a.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE a.resourceType = ?
      GROUP BY a.resourceUuid
      HAVING MAX(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, -9223372036854775808) DESC
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("given", ResourceType.Patient.name), query.args)
  }

  @Test
  fun search_filter_shouldAppendIndexNameOnly_forTokenFilter_withCodeOnly() {
    val query =
      Search(ResourceType.Condition)
        .apply {
          filter(
            TokenClientParam("clinical-status"),
            { value = of(Coding(code = Code(value = "test-code"))) },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("Condition", "clinical-status", "test-code"), query.args)
  }

  @Test
  fun search_filter_shouldAppendIndexNameAndSystem_forTokenFilter_withCodeAndSystem() {
    val query =
      Search(ResourceType.Condition)
        .apply {
          filter(
            TokenClientParam("clinical-status"),
            {
              value =
                of(
                  Coding(
                    code = Code(value = "test-code"),
                    system = Uri(value = "http://my-code-system.org"),
                  ),
                )
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "Condition",
        "clinical-status",
        "test-code",
        "http://my-code-system.org",
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_less_or_equal() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Le
              system = "http://unitsofmeasure.org"
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_system = ? AND index_value <= ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        "http://unitsofmeasure.org",
        BigDecimal.parseString("5.403").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_greater_equal() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Ge
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value >= ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        BigDecimal.parseString("5.403").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_not_equal() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Ne
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value < ? OR index_value >= ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        BigDecimal.parseString("5.4025").doubleValue(false),
        BigDecimal.parseString("5.4035").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_starts_after() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Sa
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value > ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        BigDecimal.parseString("5.403").doubleValue(false),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_quantity_ends_before() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = SearchComparator.Eb
              value = BigDecimal.parseString("5.403")
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM QuantityIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value < ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOfNotNull(
        ResourceType.Observation.name,
        "value-quantity",
        BigDecimal.parseString("5.403").doubleValue(false),
      ),
      query.args,
    )
  }

  private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDays().toLong()

  private fun startOfDayUtcMillis(date: String): Long =
    LocalDate.parse(date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

  @Test
  fun search_filter_date_starts_after() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              prefix = SearchComparator.Sa
              value = of(FhirDate.fromString("2013-03-14")!!)
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_from > ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", epochDay("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_date_approximate() {
    val now = Instant.parse("2013-03-20T12:00:00Z")
    searchClock =
      object : Clock {
        override fun now() = now
      }
    try {
      val value = FhirDate.fromString("2013-03-14")!!
      val query =
        Search(ResourceType.Patient)
          .apply {
            filter(
              DateClientParam("birthdate"),
              {
                this.value = of(value)
                prefix = SearchComparator.Ap
              },
            )
          }
          .getQuery()

      assertEquals(
        """
        SELECT a.resourceUuid, a.serializedResource
        FROM ResourceEntity a
        WHERE a.resourceUuid IN (
        SELECT resourceUuid FROM DateIndexEntity
        WHERE resourceType = ? AND index_name = ? AND (index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?)
        )
        """
          .trimIndent(),
        query.query,
      )
      assertEquals(
        listOf(ResourceType.Patient.name, "birthdate", 15777L, 15779L, 15777L, 15779L),
        query.args,
      )
    } finally {
      searchClock = Clock.System
    }
  }

  @Test
  fun search_filter_date_approximate_month() {
    val now = Instant.parse("2013-03-20T12:00:00Z")
    searchClock =
      object : Clock {
        override fun now() = now
      }
    try {
      val query =
        Search(ResourceType.Patient)
          .apply {
            filter(
              DateClientParam("birthdate"),
              {
                value = of(FhirDate.fromString("2013-02")!!)
                prefix = SearchComparator.Ap
              },
            )
          }
          .getQuery()

      // February 2013 is days 15737 to 15764. "Now" is March 2013, days 15765 to 15795.
      assertEquals(
        listOf(ResourceType.Patient.name, "birthdate", 15734L, 15767L, 15734L, 15767L),
        query.args,
      )
    } finally {
      searchClock = Clock.System
    }
  }

  @Test
  fun search_filter_dateTime_approximate() {
    val now = Instant.parse("2013-03-20T12:00:00Z")
    searchClock =
      object : Clock {
        override fun now() = now
      }
    try {
      val value = FhirDateTime.fromString("2013-03-14")!!
      val query =
        Search(ResourceType.Patient)
          .apply {
            filter(
              DateClientParam("birthdate"),
              {
                this.value = of(value)
                prefix = SearchComparator.Ap
              },
            )
          }
          .getQuery()

      assertEquals(
        """
        SELECT a.resourceUuid, a.serializedResource
        FROM ResourceEntity a
        WHERE a.resourceUuid IN (
        SELECT resourceUuid FROM DateTimeIndexEntity
        WHERE resourceType = ? AND index_name = ? AND (index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?)
        )
        """
          .trimIndent(),
        query.query,
      )
      assertEquals(
        listOf(
          ResourceType.Patient.name,
          "birthdate",
          1363167360000L,
          1363357439999L,
          1363167360000L,
          1363357439999L,
        ),
        query.args,
      )
    } finally {
      searchClock = Clock.System
    }
  }

  @Test
  fun search_filter_dateTime_approximate_month() {
    val now = Instant.parse("2013-03-20T12:00:00Z")
    searchClock =
      object : Clock {
        override fun now() = now
      }
    try {
      val query =
        Search(ResourceType.Patient)
          .apply {
            filter(
              DateClientParam("birthdate"),
              {
                value = of(FhirDateTime.fromString("2013-02")!!)
                prefix = SearchComparator.Ap
              },
            )
          }
          .getQuery()

      // February 2013 is 1359676800000 to 1362095999999 ms. "Now" is March 2013, 1362096000000 to
      // 1364774399999 ms. Each end widens by a tenth of its distance to the matching end of March.
      assertEquals(
        listOf(
          ResourceType.Patient.name,
          "birthdate",
          1359434880000L,
          1362363839999L,
          1359434880000L,
          1362363839999L,
        ),
        query.args,
      )
    } finally {
      searchClock = Clock.System
    }
  }

  @Test
  fun search_CarePlan_filter_with_large_list_of_patient_reference() {
    val references = (1..990).map { "Patient/patient-$it" }
    val criteria =
      references.map<String, ReferenceParamFilterCriterion.() -> Unit> { { value = it } }

    val query =
      Search(ResourceType.CarePlan)
        .apply { filter(ReferenceClientParam("subject"), *criteria.toTypedArray()) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (((((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR (((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))))) OR ((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))))) OR (((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))))) OR ((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))))))) OR ((((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR (((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))))) OR ((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))))) OR (((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))))) OR ((((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))))) OR (((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))) OR ((((index_value = ? OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))) OR ((((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))) OR (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))))))))))
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("CarePlan", "subject") + references, query.args)
  }

  @Test
  fun search_filter_date_ends_before() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = SearchComparator.Eb
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_to < ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", epochDay("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_date_not_equal() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = SearchComparator.Ne
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_from NOT BETWEEN ? AND ? OR index_to NOT BETWEEN ? AND ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "birthdate",
        epochDay("2013-03-14"),
        epochDay("2013-03-14"),
        epochDay("2013-03-14"),
        epochDay("2013-03-14"),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_date_equal() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(DateClientParam("birthdate"), { value = of(FhirDate.fromString("2013-03-14")!!) })
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "birthdate",
        epochDay("2013-03-14"),
        epochDay("2013-03-14"),
        epochDay("2013-03-14"),
        epochDay("2013-03-14"),
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_date_greater() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = SearchComparator.Gt
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_to > ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", epochDay("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_date_greaterOrEqual() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = SearchComparator.Ge
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_to >= ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", epochDay("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_date_less() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = SearchComparator.Lt
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_from < ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", epochDay("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_date_lessOrEqual() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = SearchComparator.Le
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_from <= ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", epochDay("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_starts_after() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Sa
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_from > ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", startOfDayUtcMillis("2013-03-15") - 1),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_ends_before() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Eb
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_to < ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", startOfDayUtcMillis("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_not_equal() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Ne
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_from NOT BETWEEN ? AND ? OR index_to NOT BETWEEN ? AND ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "birthdate",
        startOfDayUtcMillis("2013-03-14"),
        startOfDayUtcMillis("2013-03-15") - 1,
        startOfDayUtcMillis("2013-03-14"),
        startOfDayUtcMillis("2013-03-15") - 1,
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_equal() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Eq
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "birthdate",
        startOfDayUtcMillis("2013-03-14"),
        startOfDayUtcMillis("2013-03-15") - 1,
        startOfDayUtcMillis("2013-03-14"),
        startOfDayUtcMillis("2013-03-15") - 1,
      ),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_greater() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Gt
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_to > ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", startOfDayUtcMillis("2013-03-15") - 1),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_greaterOrEqual() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Ge
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_to >= ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", startOfDayUtcMillis("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_less() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Lt
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_from < ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", startOfDayUtcMillis("2013-03-14")),
      query.args,
    )
  }

  @Test
  fun search_filter_dateTime_lessOrEqual() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = SearchComparator.Le
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM DateTimeIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_from <= ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(ResourceType.Patient.name, "birthdate", startOfDayUtcMillis("2013-03-15") - 1),
      query.args,
    )
  }

  @Test
  fun search_sort_numbers_ascending() {
    val query =
      Search(ResourceType.RiskAssessment)
        .apply { sort(NumberClientParam("probability"), Order.ASCENDING) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      LEFT JOIN NumberIndexEntity b
      ON a.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE a.resourceType = ?
      GROUP BY a.resourceUuid
      HAVING MIN(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, 9223372036854775808) ASC
      """
        .trimIndent(),
      query.query,
    )
  }

  @Test
  fun search_filter_sort_size_from() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(StringClientParam("family"), { value = "Jones" })
          sort(StringClientParam("given"), Order.ASCENDING)
          count = 10
          from = 20
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      LEFT JOIN StringIndexEntity b
      ON a.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE ?
      )
      GROUP BY a.resourceUuid
      HAVING MIN(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, x'') ASC
      LIMIT ? OFFSET ?
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "given",
        ResourceType.Patient.name,
        "family",
        "Jones%",
        10,
        20,
      ),
      query.args,
    )
  }

  @Test
  fun search_date_sort() {
    val query =
      Search(ResourceType.Patient)
        .apply { sort(DateClientParam("birthdate"), Order.ASCENDING) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      LEFT JOIN DateIndexEntity b
      ON a.resourceUuid = b.resourceUuid AND b.index_name = ?
      LEFT JOIN DateTimeIndexEntity c
      ON a.resourceUuid = c.resourceUuid AND c.index_name = ?
      WHERE a.resourceType = ?
      GROUP BY a.resourceUuid
      HAVING MIN(IFNULL(b.index_from,0) + IFNULL(c.index_from,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_from, 9223372036854775808) ASC, IFNULL(c.index_from, 9223372036854775808) ASC
      """
        .trimIndent(),
      query.query,
    )
  }

  @Test
  fun search_date_sort_descending() {
    val query =
      Search(ResourceType.Patient)
        .apply { sort(DateClientParam("birthdate"), Order.DESCENDING) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      LEFT JOIN DateIndexEntity b
      ON a.resourceUuid = b.resourceUuid AND b.index_name = ?
      LEFT JOIN DateTimeIndexEntity c
      ON a.resourceUuid = c.resourceUuid AND c.index_name = ?
      WHERE a.resourceType = ?
      GROUP BY a.resourceUuid
      HAVING MAX(IFNULL(b.index_from,0) + IFNULL(c.index_from,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_from, -9223372036854775808) DESC, IFNULL(c.index_from, -9223372036854775808) DESC
      """
        .trimIndent(),
      query.query,
    )
  }

  @Test
  fun search_patient_single_search_param_multiple_values_disjunction() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "John"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
            {
              value = "Jane"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
            operation = Operation.OR,
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND ((index_value = ? AND index_value = ? COLLATE BINARY) OR (index_value = ? AND index_value = ? COLLATE BINARY))
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("Patient", "given", "John", "John", "Jane", "Jane"), query.args)
  }

  @Test
  fun search_patient_single_search_param_multiple_params_disjunction() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "John"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )

          filter(
            StringClientParam("given"),
            {
              value = "Jane"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )
          operation = Operation.OR
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND index_value = ? COLLATE BINARY)
      )
      OR a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND index_value = ? COLLATE BINARY)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf("Patient", "given", "John", "John", "Patient", "given", "Jane", "Jane"),
      query.args,
    )
  }

  // Test for https://github.com/google/android-fhir/issues/903
  @Test
  fun search_patient_search_params_single_given_multiple_family() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(StringClientParam("given"), { value = "John" })
          filter(StringClientParam("family"), { value = "Doe" }, { value = "Roe" })
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE ?
      )
      AND a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value LIKE ? OR index_value LIKE ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf("Patient", "given", "John%", "Patient", "family", "Doe%", "Roe%"),
      query.args,
    )
  }

  @Test
  fun search_has_patient_with_diabetes() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          has(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              { value = of(coding("http://snomed.info/sct", "44054006", "Diabetes")) },
            )
          }
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid
      FROM ResourceEntity a
      WHERE a.resourceType = ? AND a.resourceId IN (
      SELECT substr(a.index_value, 9)
      FROM ReferenceIndexEntity a
      WHERE a.index_name = ? AND a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      )
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "subject",
        ResourceType.Condition.name,
        "code",
        "44054006",
        "http://snomed.info/sct",
      ),
      query.args,
    )
  }

  @Test
  fun search_has_patient_with_influenza_vaccine_status_completed_in_India() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          has(
            resourceType = ResourceType.Immunization,
            referenceParam = ReferenceClientParam("patient"),
          ) {
            filter(
              TokenClientParam("vaccine-code"),
              {
                value =
                  of(
                    coding(
                      "http://hl7.org/fhir/sid/cvx",
                      "140",
                      "Influenza, seasonal, injectable, preservative free",
                    ),
                  )
              },
            )
            filter(
              TokenClientParam("status"),
              {
                value = of(coding("http://hl7.org/fhir/event-status", "completed", "Body Weight"))
              },
            )
          }

          filter(
            StringClientParam("address-country"),
            {
              modifier = StringFilterModifier.MATCHES_EXACTLY
              value = "IN"
            },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND index_value = ? COLLATE BINARY)
      )
      AND a.resourceUuid IN (
      SELECT resourceUuid
      FROM ResourceEntity a
      WHERE a.resourceType = ? AND a.resourceId IN (
      SELECT substr(a.index_value, 9)
      FROM ReferenceIndexEntity a
      WHERE a.index_name = ? AND a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      AND a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      )
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "address-country",
        "IN",
        "IN",
        ResourceType.Patient.name,
        "patient",
        ResourceType.Immunization.name,
        "vaccine-code",
        "140",
        "http://hl7.org/fhir/sid/cvx",
        ResourceType.Immunization.name,
        "status",
        "completed",
        "http://hl7.org/fhir/event-status",
      ),
      query.args,
    )
  }

  @Test
  fun search_has_patient_has_condition_diabetes_and_hypertension() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          has(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              { value = of(coding("http://snomed.info/sct", "44054006", "Diabetes")) },
            )
          }
          has(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              { value = of(coding("http://snomed.info/sct", "827069000", "Hypertension stage 1")) },
            )
          }
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid
      FROM ResourceEntity a
      WHERE a.resourceType = ? AND a.resourceId IN (
      SELECT substr(a.index_value, 9)
      FROM ReferenceIndexEntity a
      WHERE a.index_name = ? AND a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      )
      )  AND a.resourceUuid IN(
      SELECT resourceUuid
      FROM ResourceEntity a
      WHERE a.resourceType = ? AND a.resourceId IN (
      SELECT substr(a.index_value, 9)
      FROM ReferenceIndexEntity a
      WHERE a.index_name = ? AND a.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      )
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        ResourceType.Patient.name,
        "subject",
        ResourceType.Condition.name,
        "code",
        "44054006",
        "http://snomed.info/sct",
        ResourceType.Patient.name,
        "subject",
        ResourceType.Condition.name,
        "code",
        "827069000",
        "http://snomed.info/sct",
      ),
      query.args,
    )
  }

  @Test
  fun search_revInclude_all_conditions_for_patients() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          revInclude(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          )
        }
        .getRevIncludeQuery(listOf("Patient/pa01", "Patient/pa02"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.index_value, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceUuid = rie.resourceUuid
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.index_value IN (?, ?)
      AND re.resourceType = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf("Condition", "subject", "Patient/pa01", "Patient/pa02", "Condition"),
      query.args,
    )
  }

  @Test
  fun search_revInclude_diabetic_conditions_for_patients() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          revInclude(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              {
                value =
                  of(
                    CodeableConcept(
                      coding = listOf(coding("http://snomed.info/sct", "44054006", "Diabetes")),
                    ),
                  )
              },
            )
          }
        }
        .getRevIncludeQuery(listOf("Patient/pa01", "Patient/pa02"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.index_value, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceUuid = rie.resourceUuid
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.index_value IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "Condition",
        "subject",
        "Patient/pa01",
        "Patient/pa02",
        "Condition",
        "code",
        "44054006",
        "http://snomed.info/sct",
      ),
      query.args,
    )
  }

  @Test
  fun search_revInclude_diabetic_conditions_for_patients_and_sort_by_recorded_date() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          revInclude(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              {
                value =
                  of(
                    CodeableConcept(
                      coding = listOf(coding("http://snomed.info/sct", "44054006", "Diabetes")),
                    ),
                  )
              },
            )
            sort(DateClientParam("recorded-date"), Order.DESCENDING)
          }
        }
        .getRevIncludeQuery(listOf("Patient/pa01", "Patient/pa02"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.index_value, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceUuid = rie.resourceUuid
      LEFT JOIN DateIndexEntity b
      ON re.resourceUuid = b.resourceUuid AND b.index_name = ?
      LEFT JOIN DateTimeIndexEntity c
      ON re.resourceUuid = c.resourceUuid AND c.index_name = ?
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.index_value IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      GROUP BY re.resourceUuid , rie.index_value
      HAVING MAX(IFNULL(b.index_from,0) + IFNULL(c.index_from,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_from, -9223372036854775808) DESC, IFNULL(c.index_from, -9223372036854775808) DESC
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "recorded-date",
        "recorded-date",
        "Condition",
        "subject",
        "Patient/pa01",
        "Patient/pa02",
        "Condition",
        "code",
        "44054006",
        "http://snomed.info/sct",
      ),
      query.args,
    )
  }

  @Test
  fun search_revInclude_encounters_and_conditions_filtered_and_sorted() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          revInclude(
            resourceType = ResourceType.Encounter,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("status"),
              { value = of(coding("http://hl7.org/fhir/encounter-status", "arrived", "")) },
            )
            sort(DateClientParam("date"), Order.DESCENDING)
          }

          revInclude(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              {
                value =
                  of(
                    CodeableConcept(
                      coding = listOf(coding("http://snomed.info/sct", "44054006", "Diabetes")),
                    ),
                  )
              },
            )
            sort(DateClientParam("recorded-date"), Order.DESCENDING)
          }
        }
        .getRevIncludeQuery(listOf("Patient/pa01", "Patient/pa02"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.index_value, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceUuid = rie.resourceUuid
      LEFT JOIN DateIndexEntity b
      ON re.resourceUuid = b.resourceUuid AND b.index_name = ?
      LEFT JOIN DateTimeIndexEntity c
      ON re.resourceUuid = c.resourceUuid AND c.index_name = ?
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.index_value IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      GROUP BY re.resourceUuid , rie.index_value
      HAVING MAX(IFNULL(b.index_from,0) + IFNULL(c.index_from,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_from, -9223372036854775808) DESC, IFNULL(c.index_from, -9223372036854775808) DESC
      )
      UNION ALL
      SELECT * FROM (
      SELECT rie.index_name, rie.index_value, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceUuid = rie.resourceUuid
      LEFT JOIN DateIndexEntity b
      ON re.resourceUuid = b.resourceUuid AND b.index_name = ?
      LEFT JOIN DateTimeIndexEntity c
      ON re.resourceUuid = c.resourceUuid AND c.index_name = ?
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.index_value IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? AND IFNULL(index_system,'') = ?)
      )
      GROUP BY re.resourceUuid , rie.index_value
      HAVING MAX(IFNULL(b.index_from,0) + IFNULL(c.index_from,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_from, -9223372036854775808) DESC, IFNULL(c.index_from, -9223372036854775808) DESC
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "date",
        "date",
        "Encounter",
        "subject",
        "Patient/pa01",
        "Patient/pa02",
        "Encounter",
        "status",
        "arrived",
        "http://hl7.org/fhir/encounter-status",
        "recorded-date",
        "recorded-date",
        "Condition",
        "subject",
        "Patient/pa01",
        "Patient/pa02",
        "Condition",
        "code",
        "44054006",
        "http://snomed.info/sct",
      ),
      query.args,
    )
  }

  @Test
  fun search_include_all_practitioners() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          include(
            resourceType = ResourceType.Practitioner,
            referenceParam = ReferenceClientParam("general-practitioner"),
          )
        }
        .getIncludeQuery(listOf(UUID_1, UUID_2))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType = substr(rie.index_value, 1, instr(rie.index_value, '/') - 1)
      AND re.resourceId = substr(rie.index_value, instr(rie.index_value, '/') + 1)
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.resourceUuid IN (?, ?)
      AND re.resourceType = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "Patient",
        "general-practitioner",
        UUID_1,
        UUID_2,
        "Practitioner",
      ),
      query.args,
    )
  }

  @Test
  fun search_include_all_active_practitioners() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          include(
            resourceType = ResourceType.Practitioner,
            referenceParam = ReferenceClientParam("general-practitioner"),
          ) {
            filter(TokenClientParam("active"), { value = of(true) })
          }
        }
        .getIncludeQuery(listOf(UUID_1, UUID_2))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType = substr(rie.index_value, 1, instr(rie.index_value, '/') - 1)
      AND re.resourceId = substr(rie.index_value, instr(rie.index_value, '/') + 1)
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.resourceUuid IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "Patient",
        "general-practitioner",
        UUID_1,
        UUID_2,
        "Practitioner",
        "active",
        "true",
      ),
      query.args,
    )
  }

  @Test
  fun search_include_all_active_practitioners_and_sort_by_given_name() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          include(
            resourceType = ResourceType.Practitioner,
            referenceParam = ReferenceClientParam("general-practitioner"),
          ) {
            filter(TokenClientParam("active"), { value = of(true) })
            sort(StringClientParam("given"), Order.DESCENDING)
          }
        }
        .getIncludeQuery(listOf(UUID_1, UUID_2))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType = substr(rie.index_value, 1, instr(rie.index_value, '/') - 1)
      AND re.resourceId = substr(rie.index_value, instr(rie.index_value, '/') + 1)
      LEFT JOIN StringIndexEntity b
      ON re.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.resourceUuid IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      GROUP BY re.resourceUuid , rie.resourceUuid
      HAVING MAX(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, -9223372036854775808) DESC
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "given",
        "Patient",
        "general-practitioner",
        UUID_1,
        UUID_2,
        "Practitioner",
        "active",
        "true",
      ),
      query.args,
    )
  }

  @Test
  fun search_include_practitioners_and_organizations() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          include(
            resourceType = ResourceType.Practitioner,
            referenceParam = ReferenceClientParam("general-practitioner"),
          ) {
            filter(TokenClientParam("active"), { value = of(true) })
            sort(StringClientParam("given"), Order.DESCENDING)
          }

          include(
            resourceType = ResourceType.Organization,
            referenceParam = ReferenceClientParam("organization"),
          ) {
            filter(TokenClientParam("active"), { value = of(true) })
            sort(StringClientParam("name"), Order.DESCENDING)
          }
        }
        .getIncludeQuery(listOf(UUID_1, UUID_2))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType = substr(rie.index_value, 1, instr(rie.index_value, '/') - 1)
      AND re.resourceId = substr(rie.index_value, instr(rie.index_value, '/') + 1)
      LEFT JOIN StringIndexEntity b
      ON re.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.resourceUuid IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      GROUP BY re.resourceUuid , rie.resourceUuid
      HAVING MAX(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, -9223372036854775808) DESC
      )
      UNION ALL
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType = substr(rie.index_value, 1, instr(rie.index_value, '/') - 1)
      AND re.resourceId = substr(rie.index_value, instr(rie.index_value, '/') + 1)
      LEFT JOIN StringIndexEntity b
      ON re.resourceUuid = b.resourceUuid AND b.index_name = ?
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.resourceUuid IN (?, ?)
      AND re.resourceUuid IN (
      SELECT resourceUuid FROM TokenIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      GROUP BY re.resourceUuid , rie.resourceUuid
      HAVING MAX(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, -9223372036854775808) DESC
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "given",
        "Patient",
        "general-practitioner",
        UUID_1,
        UUID_2,
        "Practitioner",
        "active",
        "true",
        "name",
        "Patient",
        "organization",
        UUID_1,
        UUID_2,
        "Organization",
        "active",
        "true",
      ),
      query.args,
    )
  }

  @Test
  fun search_CarePlan_filter_with_no_reference() {
    val query =
      Search(ResourceType.CarePlan).apply { filter(ReferenceClientParam("subject")) }.getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("CarePlan", "subject"), query.args)
  }

  @Test
  fun search_CarePlan_filter_with_one_patient_reference() {
    val query =
      Search(ResourceType.CarePlan)
        .apply { filter(ReferenceClientParam("subject"), { value = "Patient/patient-0" }) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("CarePlan", "subject", "Patient/patient-0"), query.args)
  }

  @Test
  fun search_CarePlan_filter_with_two_patient_references() {
    val query =
      Search(ResourceType.CarePlan)
        .apply {
          filter(
            ReferenceClientParam("subject"),
            { value = "Patient/patient-0" },
            { value = "Patient/patient-1" },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? OR index_value = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf("CarePlan", "subject", "Patient/patient-0", "Patient/patient-1"),
      query.args,
    )
  }

  @Test
  fun search_CarePlan_filter_with_three_patient_references() {
    val query =
      Search(ResourceType.CarePlan)
        .apply {
          filter(
            ReferenceClientParam("subject"),
            { value = "Patient/patient-0" },
            { value = "Patient/patient-1" },
            { value = "Patient/patient-4" },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? OR (index_value = ? OR index_value = ?))
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "CarePlan",
        "subject",
        "Patient/patient-0",
        "Patient/patient-1",
        "Patient/patient-4",
      ),
      query.args,
    )
  }

  @Test
  fun search_CarePlan_filter_with_four_patient_references() {
    val query =
      Search(ResourceType.CarePlan)
        .apply {
          filter(
            ReferenceClientParam("subject"),
            { value = "Patient/patient-0" },
            { value = "Patient/patient-1" },
            { value = "Patient/patient-4" },
            { value = "Patient/patient-7" },
          )
        }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ? AND ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?))
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf(
        "CarePlan",
        "subject",
        "Patient/patient-0",
        "Patient/patient-1",
        "Patient/patient-4",
        "Patient/patient-7",
      ),
      query.args,
    )
  }

  @Test
  fun search_CarePlan_filter_with_8_patient_references() {
    val patientIdReferenceList = (0..7).map { "Patient/patient-$it" }
    val patientIdList =
      patientIdReferenceList.map<String, ReferenceParamFilterCriterion.() -> Unit> {
        { value = it }
      }
    val query =
      Search(ResourceType.CarePlan)
        .apply { filter(ReferenceClientParam("subject"), *patientIdList.toTypedArray()) }
        .getQuery()

    assertEquals(
      """
      SELECT a.resourceUuid, a.serializedResource
      FROM ResourceEntity a
      WHERE a.resourceUuid IN (
      SELECT resourceUuid FROM ReferenceIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)) OR ((index_value = ? OR index_value = ?) OR (index_value = ? OR index_value = ?)))
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf("CarePlan", "subject", *patientIdReferenceList.toTypedArray()),
      query.args,
    )
  }
}

private fun coding(system: String, code: String, display: String) =
  Coding(
    system = Uri(value = system),
    code = Code(value = code),
    display = FhirString(value = display),
  )

private val UUID_1 = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val UUID_2 = Uuid.parse("00000000-0000-0000-0000-000000000002")
