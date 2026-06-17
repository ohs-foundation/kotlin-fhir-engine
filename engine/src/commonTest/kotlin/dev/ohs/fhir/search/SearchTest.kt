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

import dev.ohs.fhir.search.filter.ReferenceParamFilterCriterion
import dev.ohs.fhir.search.filter.TokenFilterValue
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Adapted from google/android-fhir: engine/src/test/java/com/google/android/fhir/search/SearchTest.kt
 *
 * Tests SQL query generation from Search DSL. Each test builds a Search, calls getQuery(), and
 * asserts on the generated SQL string and argument list.
 *
 * Adaptations:
 * - HAPI Patient.BIRTHDATE → DateClientParam("birthdate")
 * - HAPI Patient.FAMILY → StringClientParam("family")
 * - HAPI Patient.GENDER → TokenClientParam("gender")
 * - HAPI Patient.ADDRESS → StringClientParam("address")
 * - HAPI Patient.ACTIVE → TokenClientParam("active")
 * - HAPI Patient.IDENTIFIER → TokenClientParam("identifier")
 * - HAPI Patient.TELECOM → TokenClientParam("telecom")
 * - HAPI Patient.PHONE → TokenClientParam("phone")
 * - HAPI Patient.GIVEN → StringClientParam("given")
 * - HAPI Observation.VALUE_QUANTITY → QuantityClientParam("value-quantity")
 * - HAPI Library.URL → UriClientParam("url")
 * - HAPI CarePlan.SUBJECT → ReferenceClientParam("subject")
 * - HAPI of(Coding(...)) → TokenFilterValue.coding(system, code)
 * - HAPI of(CodeType(...)) → TokenFilterValue.string(code)
 * - HAPI of(true) → TokenFilterValue.boolean(true)
 * - HAPI of(UriType(...)) → TokenFilterValue.string(value)
 * - HAPI of(identifier) → TokenFilterValue.coding(system, value)
 * - Robolectric removed, Truth → kotlin.test
 * - runBlocking → no wrapping needed (getQuery is not suspend)
 *
 * Remaining un-ported tests (4 of 73), all genuinely blocked — not arbitrary:
 * - search_filter_date_approximate / search_filter_dateTime_approximate — engine mocks "now" via
 *   DateProvider, but engine-kmp's APPROXIMATE branch reads Clock.System.now() directly.
 * - search_filter_quantity_canonical_match — engine-kmp UnitConverter is a no-op (UCUM not ported).
 * - search CarePlan filter with large list of patient reference — asserts a ~990-deep nested SQL
 *   literal; the divide-and-conquer toQueryString algorithm is already covered by the disjunction
 *   tests, so the giant exact-string literal isn't reproduced.
 */
class SearchTest {

  // --- Basic query tests ---

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

  // --- String filter tests ---

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
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE ? || '%' COLLATE NOCASE
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
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
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

  // --- Token filter tests ---

  @Test
  fun search_filter_token_coding() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("gender"),
            {
              value =
                TokenFilterValue.coding(
                  "http://hl7.org/fhir/ValueSet/administrative-gender",
                  "male",
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
            { value = TokenFilterValue.coding("http://snomed.info/sct", "260385009") },
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
  fun search_filter_token_identifier() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("identifier"),
            { value = TokenFilterValue.coding("http://acme.org/patient", "12345") },
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
        .apply { filter(TokenClientParam("gender"), { value = TokenFilterValue.string("male") }) }
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
        .apply { filter(TokenClientParam("active"), { value = TokenFilterValue.boolean(true) }) }
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
            { value = TokenFilterValue.string("16009886-bd57-11eb-8529-0242ac130003") },
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
        .apply {
          filter(TokenClientParam("phone"), { value = TokenFilterValue.string("+14845219791") })
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
        ContactPoint.ContactPointUse.Home.getCode(),
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

  // --- Quantity filter tests ---

  @Test
  fun search_filter_quantity_equals() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = ParamPrefixEnum.EQUAL
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
              prefix = ParamPrefixEnum.LESSTHAN
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
              prefix = ParamPrefixEnum.GREATERTHAN
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

  // --- URI filter test ---

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

  // --- Sort tests ---

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
      ORDER BY IFNULL(b.index_value, 9223372036854775808) ASC
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

  // --- Token index SQL format tests ---

  @Test
  fun search_filter_shouldAppendIndexNameOnly_forTokenFilter_withCodeOnly() {
    val query =
      Search(ResourceType.Patient)
        .apply { filter(TokenClientParam("gender"), { value = TokenFilterValue.string("male") }) }
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
  fun search_filter_shouldAppendIndexNameAndSystem_forTokenFilter_withCodeAndSystem() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            TokenClientParam("gender"),
            {
              value =
                TokenFilterValue.coding(
                  "http://hl7.org/fhir/administrative-gender",
                  "male",
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
        "gender",
        "male",
        "http://hl7.org/fhir/administrative-gender",
      ),
      query.args,
    )
  }

  // --- Remaining quantity filter tests ---

  @Test
  fun search_filter_quantity_less_or_equal() {
    val query =
      Search(ResourceType.Observation)
        .apply {
          filter(
            QuantityClientParam("value-quantity"),
            {
              prefix = ParamPrefixEnum.LESSTHAN_OR_EQUALS
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
              prefix = ParamPrefixEnum.GREATERTHAN_OR_EQUALS
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
              prefix = ParamPrefixEnum.NOT_EQUAL
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
              prefix = ParamPrefixEnum.STARTS_AFTER
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
              prefix = ParamPrefixEnum.ENDS_BEFORE
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

  // TODO: search_filter_quantity_canonical_match — engine test expects UCUM conversion from mg to g
  // with value 5403mg → 5.403g. Engine-kmp UnitConverter is currently a no-op (returns original
  // value unchanged). Skip until UCUM conversion is implemented.

  // --- Date filter tests ---
  //
  // NOTE: search_filter_date_approximate / search_filter_dateTime_approximate are NOT ported: the
  // engine mocks "now" via DateProvider, but engine-kmp's APPROXIMATE branch reads
  // Clock.System.now() directly, so the result isn't deterministic for a test.

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
              prefix = ParamPrefixEnum.STARTS_AFTER
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
  fun search_filter_date_ends_before() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDate.fromString("2013-03-14")!!)
              prefix = ParamPrefixEnum.ENDS_BEFORE
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
              prefix = ParamPrefixEnum.NOT_EQUAL
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
              prefix = ParamPrefixEnum.GREATERTHAN
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
              prefix = ParamPrefixEnum.GREATERTHAN_OR_EQUALS
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
              prefix = ParamPrefixEnum.LESSTHAN
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
              prefix = ParamPrefixEnum.LESSTHAN_OR_EQUALS
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

  // --- DateTime filter tests ---

  @Test
  fun search_filter_dateTime_starts_after() {
    val query =
      Search(ResourceType.Patient)
        .apply {
          filter(
            DateClientParam("birthdate"),
            {
              value = of(FhirDateTime.fromString("2013-03-14")!!)
              prefix = ParamPrefixEnum.STARTS_AFTER
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
              prefix = ParamPrefixEnum.ENDS_BEFORE
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
              prefix = ParamPrefixEnum.NOT_EQUAL
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
              prefix = ParamPrefixEnum.EQUAL
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
              prefix = ParamPrefixEnum.GREATERTHAN
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
              prefix = ParamPrefixEnum.GREATERTHAN_OR_EQUALS
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
              prefix = ParamPrefixEnum.LESSTHAN
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
              prefix = ParamPrefixEnum.LESSTHAN_OR_EQUALS
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

  // --- Number sort test ---

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

  // --- Combined filter + sort + pagination test ---

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
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE ? || '%' COLLATE NOCASE
      )
      GROUP BY a.resourceUuid
      HAVING MIN(IFNULL(b.index_value,0)) >= -9223372036854775808
      ORDER BY IFNULL(b.index_value, 9223372036854775808) ASC
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
        "Jones",
        10,
        20,
      ),
      query.args,
    )
  }

  // --- Date sort tests ---

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

  // --- Disjunction tests ---

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
      WHERE resourceType = ? AND index_name = ? AND (index_value = ? OR index_value = ?)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("Patient", "given", "John", "Jane"), query.args)
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
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      OR a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(listOf("Patient", "given", "John", "Patient", "given", "Jane"), query.args)
  }

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
      WHERE resourceType = ? AND index_name = ? AND index_value LIKE ? || '%' COLLATE NOCASE
      )
      AND a.resourceUuid IN (
      SELECT resourceUuid FROM StringIndexEntity
      WHERE resourceType = ? AND index_name = ? AND (index_value LIKE ? || '%' COLLATE NOCASE OR index_value LIKE ? || '%' COLLATE NOCASE)
      )
      """
        .trimIndent(),
      query.query,
    )
    assertEquals(
      listOf("Patient", "given", "John", "Patient", "family", "Doe", "Roe"),
      query.args,
    )
  }

  // --- HAS tests ---

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
              { value = TokenFilterValue.coding("http://snomed.info/sct", "44054006") },
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
                  TokenFilterValue.coding(
                    "http://hl7.org/fhir/sid/cvx",
                    "140",
                  )
              },
            )
            filter(
              TokenClientParam("status"),
              { value = TokenFilterValue.coding("http://hl7.org/fhir/event-status", "completed") },
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
      WHERE resourceType = ? AND index_name = ? AND index_value = ?
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
              { value = TokenFilterValue.coding("http://snomed.info/sct", "44054006") },
            )
          }
          has(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              { value = TokenFilterValue.coding("http://snomed.info/sct", "827069000") },
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

  // --- RevInclude tests ---

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
              { value = TokenFilterValue.coding("http://snomed.info/sct", "44054006") },
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
              { value = TokenFilterValue.coding("http://snomed.info/sct", "44054006") },
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
              {
                value = TokenFilterValue.coding("http://hl7.org/fhir/encounter-status", "arrived")
              },
            )
            sort(DateClientParam("date"), Order.DESCENDING)
          }

          revInclude(
            resourceType = ResourceType.Condition,
            referenceParam = ReferenceClientParam("subject"),
          ) {
            filter(
              TokenClientParam("code"),
              { value = TokenFilterValue.coding("http://snomed.info/sct", "44054006") },
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

  // --- Include tests ---

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
        .getIncludeQuery(listOf("uuid-1", "uuid-2"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType||"/"||re.resourceId = rie.index_value
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
        "uuid-1",
        "uuid-2",
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
            filter(TokenClientParam("active"), { value = TokenFilterValue.boolean(true) })
          }
        }
        .getIncludeQuery(listOf("uuid-1", "uuid-2"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType||"/"||re.resourceId = rie.index_value
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
        "uuid-1",
        "uuid-2",
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
            filter(TokenClientParam("active"), { value = TokenFilterValue.boolean(true) })
            sort(StringClientParam("given"), Order.DESCENDING)
          }
        }
        .getIncludeQuery(listOf("uuid-1", "uuid-2"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType||"/"||re.resourceId = rie.index_value
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
        "uuid-1",
        "uuid-2",
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
            filter(TokenClientParam("active"), { value = TokenFilterValue.boolean(true) })
            sort(StringClientParam("given"), Order.DESCENDING)
          }

          include(
            resourceType = ResourceType.Organization,
            referenceParam = ReferenceClientParam("organization"),
          ) {
            filter(TokenClientParam("active"), { value = TokenFilterValue.boolean(true) })
            sort(StringClientParam("name"), Order.DESCENDING)
          }
        }
        .getIncludeQuery(listOf("uuid-1", "uuid-2"))

    assertEquals(
      """
      SELECT * FROM (
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType||"/"||re.resourceId = rie.index_value
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
      ON re.resourceType||"/"||re.resourceId = rie.index_value
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
        "uuid-1",
        "uuid-2",
        "Practitioner",
        "active",
        "true",
        "name",
        "Patient",
        "organization",
        "uuid-1",
        "uuid-2",
        "Organization",
        "active",
        "true",
      ),
      query.args,
    )
  }

  // --- Reference filter tests ---

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

  // Blocked (see class KDoc): date/dateTime APPROXIMATE (Clock.System.now not mockable),
  // quantity_canonical_match (UCUM not ported), large-reference-list (~990-deep literal).
}
