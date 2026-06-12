/*
 * Copyright 2021-2026 Google LLC
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

import dev.ohs.fhir.model.r4.terminologies.ResourceType
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Adapted from engine/src/test/java/com/google/android/fhir/search/NumberSearchParameterizedTest.kt
 *
 * KMP adaptations:
 * - ParameterizedRobolectricTestRunner is unavailable; each parameterized test instead loops over
 *   the [cases] list internally.
 * - HAPI `RiskAssessment.PROBABILITY` → `NumberClientParam("probability")`.
 * - `java.math.BigDecimal` → ionspin `BigDecimal`; `.toDouble()` → `.doubleValue(false)`.
 * - Truth → kotlin.test; JUnit → kotlin.test.
 */
class NumberSearchParameterizedTest {

  private data class Case(
    val num: BigDecimal,
    val lowerBound: BigDecimal,
    val upperBound: BigDecimal,
  )

  private val cases =
    listOf(
      Case(
        BigDecimal.parseString("100.00"),
        BigDecimal.parseString("99.995"),
        BigDecimal.parseString("100.005"),
      ),
      Case(
        BigDecimal.parseString("100.0"),
        BigDecimal.parseString("99.95"),
        BigDecimal.parseString("100.05"),
      ),
      Case(
        BigDecimal.parseString("0.1"),
        BigDecimal.parseString("0.05"),
        BigDecimal.parseString("0.15"),
      ),
    )

  private val baseQuery: String =
    """
    SELECT a.resourceUuid, a.serializedResource
    FROM ResourceEntity a
    WHERE a.resourceUuid IN (
    SELECT resourceUuid FROM NumberIndexEntity
    """
      .trimIndent()

  @Test
  fun `should search equal values`() {
    cases.forEach { (num, lowerBound, upperBound) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.EQUAL
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND (index_value >= ? AND index_value < ?)
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(
          ResourceType.RiskAssessment.name,
          "probability",
          lowerBound.doubleValue(false),
          upperBound.doubleValue(false),
        ),
        search.args,
      )
    }
  }

  @Test
  fun `should search unequal values`() {
    cases.forEach { (num, lowerBound, upperBound) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.NOT_EQUAL
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND (index_value < ? OR index_value >= ?)
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(
          ResourceType.RiskAssessment.name,
          "probability",
          lowerBound.doubleValue(false),
          upperBound.doubleValue(false),
        ),
        search.args,
      )
    }
  }

  @Test
  fun `should search values greater than a number`() {
    cases.forEach { (num, _, _) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.GREATERTHAN
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND index_value > ?
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(ResourceType.RiskAssessment.name, "probability", num.doubleValue(false)),
        search.args,
      )
    }
  }

  @Test
  fun `should search values greater than or equal to a number`() {
    cases.forEach { (num, _, _) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.GREATERTHAN_OR_EQUALS
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND index_value >= ?
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(ResourceType.RiskAssessment.name, "probability", num.doubleValue(false)),
        search.args,
      )
    }
  }

  @Test
  fun `should search values less than a number`() {
    cases.forEach { (num, _, _) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.LESSTHAN
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND index_value < ?
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(ResourceType.RiskAssessment.name, "probability", num.doubleValue(false)),
        search.args,
      )
    }
  }

  @Test
  fun `should search values less than or equal to a number`() {
    cases.forEach { (num, _, _) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.LESSTHAN_OR_EQUALS
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND index_value <= ?
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(ResourceType.RiskAssessment.name, "probability", num.doubleValue(false)),
        search.args,
      )
    }
  }

  @Test
  fun `should throw error when ENDS_BEFORE prefix given with integer value`() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.ENDS_BEFORE
                value = BigDecimal.parseString("100")
              },
            )
          }
          .getQuery()
      }
    assertEquals("Prefix ENDS_BEFORE not allowed for Integer type", exception.message)
  }

  @Test
  fun `should search value when ENDS_BEFORE prefix given with decimal value`() {
    cases.forEach { (num, _, _) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.ENDS_BEFORE
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND index_value < ?
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(ResourceType.RiskAssessment.name, "probability", num.doubleValue(false)),
        search.args,
      )
    }
  }

  @Test
  fun `should throw error when STARTS_AFTER prefix given with integer value`() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.STARTS_AFTER
                value = BigDecimal.fromInt(100)
              },
            )
          }
          .getQuery()
      }
    assertEquals("Prefix STARTS_AFTER not allowed for Integer type", exception.message)
  }

  @Test
  fun `should search value when STARTS_AFTER prefix given with decimal value`() {
    cases.forEach { (num, _, _) ->
      val search =
        Search(ResourceType.RiskAssessment)
          .apply {
            filter(
              NumberClientParam("probability"),
              {
                prefix = ParamPrefixEnum.STARTS_AFTER
                value = num
              },
            )
          }
          .getQuery()
      assertEquals(
        """
        |$baseQuery
        |WHERE resourceType = ? AND index_name = ? AND index_value > ?
        |)
        """
          .trimMargin(),
        search.query,
      )
      assertEquals(
        listOf(ResourceType.RiskAssessment.name, "probability", num.doubleValue(false)),
        search.args,
      )
    }
  }

  @Test
  fun `should search approximate values`() {
    val search =
      Search(ResourceType.RiskAssessment)
        .apply {
          filter(
            NumberClientParam("probability"),
            {
              prefix = ParamPrefixEnum.APPROXIMATE
              value = BigDecimal.parseString("0.1")
            },
          )
        }
        .getQuery()
    assertEquals(
      """
      |$baseQuery
      |WHERE resourceType = ? AND index_name = ? AND (index_value >= ? AND index_value <= ?)
      |)
      """
        .trimMargin(),
      search.query,
    )
    assertEquals(
      listOf(ResourceType.RiskAssessment.name, "probability", 0.09, 0.11),
      search.args,
    )
  }
}
