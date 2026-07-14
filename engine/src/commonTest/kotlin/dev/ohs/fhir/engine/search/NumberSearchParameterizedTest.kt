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
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.terminologies.ResourceType
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
                prefix = SearchComparator.Eq
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
                prefix = SearchComparator.Ne
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
                prefix = SearchComparator.Gt
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
                prefix = SearchComparator.Ge
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
                prefix = SearchComparator.Lt
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
                prefix = SearchComparator.Le
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
                prefix = SearchComparator.Eb
                value = BigDecimal.parseString("100")
              },
            )
          }
          .getQuery()
      }
    assertEquals("Prefix eb not allowed for Integer type", exception.message)
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
                prefix = SearchComparator.Eb
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
                prefix = SearchComparator.Sa
                value = BigDecimal.fromInt(100)
              },
            )
          }
          .getQuery()
      }
    assertEquals("Prefix sa not allowed for Integer type", exception.message)
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
                prefix = SearchComparator.Sa
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
              prefix = SearchComparator.Ap
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
              prefix = SearchComparator.Eq
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
