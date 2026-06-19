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

import dev.ohs.fhir.model.r4.terminologies.ResourceType
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Unit tests for when [NumberParamFilterCriterion] used in [MoreSearch]. */
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
  fun search_equalValues() {
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
  fun search_unequalValues() {
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
  fun search_valuesGreaterThanNumber() {
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
  fun search_valuesGreaterThanOrEqualToNumber() {
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
  fun search_valuesLessThanNumber() {
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
  fun search_valuesLessThanOrEqualToNumber() {
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
  fun search_endsBeforePrefixWithIntegerValue_throwsError() {
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
  fun search_endsBeforePrefixWithDecimalValue() {
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
  fun search_startsAfterPrefixWithIntegerValue_throwsError() {
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
  fun search_startsAfterPrefixWithDecimalValue() {
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
  fun search_approximateValues() {
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

  @Test
  fun search_integerValue_usesHalfUnitImplicitPrecision() {
    val search =
      Search(ResourceType.RiskAssessment)
        .apply {
          filter(
            NumberClientParam("probability"),
            {
              prefix = ParamPrefixEnum.EQUAL
              value = BigDecimal.parseString("100")
            },
          )
        }
        .getQuery()
    assertEquals(
      listOf(
        ResourceType.RiskAssessment.name,
        "probability",
        BigDecimal.parseString("99.5").doubleValue(false),
        BigDecimal.parseString("100.5").doubleValue(false),
      ),
      search.args,
    )
  }
}
