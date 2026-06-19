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

import co.touchlab.kermit.Logger
import dev.ohs.fhir.SearchResult
import dev.ohs.fhir.UcumValue
import dev.ohs.fhir.toEqualCanonical
import dev.ohs.fhir.db.Database
import dev.ohs.fhir.resourceType
import dev.ohs.fhir.ucumUrl
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Resource
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.math.absoluteValue
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * The multiplier used to determine the range for the `ap` search prefix. See
 * https://www.hl7.org/fhir/search.html#prefix for more details.
 */
private const val APPROXIMATION_COEFFICIENT = 0.1

/**
 * SQLite supports signed and unsigned integers with a maximum length of 8 bytes. The signed integers
 * can range from `-9223372036854775808` to `+9223372036854775807`. See
 * [Storage Classes and Datatypes](https://www.sqlite.org/datatype3.html)
 */
private const val MIN_VALUE = "-9223372036854775808"
private const val MAX_VALUE = "9223372036854775808"

/**
 * Executes this search against [database]: runs the base query, then any `_include` (forward)
 * and `_revinclude` (reverse) reference queries, and assembles the matched [SearchResult]s with
 * their included/revIncluded resources grouped by search index.
 */
internal suspend fun <R : Resource> Search.execute(database: Database): List<SearchResult<R>> {
  val baseResources = database.search<R>(getQuery())

  val includedResources =
    if (forwardIncludes.isEmpty() || baseResources.isEmpty()) {
      null
    } else {
      val uuids = baseResources.map { it.uuid.toString() }
      database.searchForwardReferencedResources(getIncludeQuery(uuids))
    }

  val revIncludedResources =
    if (revIncludes.isEmpty() || baseResources.isEmpty()) {
      null
    } else {
      val typeIdPairs = baseResources.map { "${it.resource.resourceType}/${it.resource.id.orEmpty()}" }
      database.searchReverseReferencedResources(getRevIncludeQuery(typeIdPairs))
    }

  return baseResources.map { (uuid, baseResource) ->
    SearchResult(
      baseResource,
      included =
        includedResources
          ?.asSequence()
          ?.filter { it.baseResourceUUID == uuid }
          ?.groupBy({ it.searchIndex }, { it.resource }),
      revIncluded =
        revIncludedResources
          ?.asSequence()
          ?.filter {
            it.baseResourceTypeWithId ==
              "${(baseResource as Resource).resourceType}/${baseResource.id.orEmpty()}"
          }
          ?.groupBy(
            {
              dev.ohs.fhir.model.r4.terminologies.ResourceType.fromCode(
                it.resource.resourceType,
              ) to it.searchIndex
            },
            { it.resource },
          ),
    )
  }
}

/** Returns the number of resources matching this search. */
internal suspend fun Search.count(database: Database): Long {
  return database.count(getQuery(true))
}

/** Builds the SQL [SearchQuery] for this search; pass [isCount] to produce a `COUNT(*)` query. */
fun Search.getQuery(isCount: Boolean = false): SearchQuery {
  return getQuery(isCount, null)
}

/**
 * Builds the SQL [SearchQuery] loading resources that reference the base results via
 * `_revinclude` (one `UNION ALL` branch per reverse include). [includeIds] are the base results'
 * `type/id` strings.
 */
internal fun Search.getRevIncludeQuery(includeIds: List<String>): SearchQuery {
  val args = mutableListOf<Any>()
  val uuidsString = CharArray(includeIds.size) { '?' }.joinToString()

  fun generateFilterQuery(nestedSearch: NestedSearch): String {
    val (param, search) = nestedSearch
    val resourceToInclude = search.type
    args.add(resourceToInclude.name)
    args.add(param.paramName)
    args.addAll(includeIds)

    var filterQuery = ""
    val filters = search.getFilterQueries()
    val iterator = filters.listIterator()
    while (iterator.hasNext()) {
      iterator.next().let {
        filterQuery += it.query
        args.addAll(it.args)
      }
      if (iterator.hasNext()) {
        filterQuery += if (search.operation == Operation.OR) "\n UNION \n" else "\n INTERSECT \n"
      }
    }
    if (filters.isEmpty()) args.add(resourceToInclude.name)
    return filterQuery
  }

  return revIncludes
    .map {
      val (join, order) =
        it.search.getSortOrder(otherTable = "re", groupByColumn = "rie.index_value")
      args.addAll(join.args)
      val filterQuery = generateFilterQuery(it)
      """
      SELECT rie.index_name, rie.index_value, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceUuid = rie.resourceUuid
      ${join.query}
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.index_value IN ($uuidsString)
      ${if (filterQuery.isNotBlank()) "AND re.resourceUuid IN ($filterQuery)" else "AND re.resourceType = ?"}
      $order
      """
        .trimIndent()
    }
    .joinToString("\nUNION ALL\n") {
      StringBuilder("SELECT * FROM (\n").append(it.trim()).append("\n)")
    }
    .split("\n")
    .filter { it.isNotBlank() }
    .joinToString("\n") { it.trim() }
    .let { SearchQuery(it, args) }
}

/**
 * Builds the SQL [SearchQuery] loading resources referenced by the base results via `_include`
 * (one `UNION ALL` branch per forward include). [includeIds] are the base results' resource UUIDs.
 */
internal fun Search.getIncludeQuery(includeIds: List<String>): SearchQuery {
  val args = mutableListOf<Any>()
  val baseResourceType = type
  val uuidsString = CharArray(includeIds.size) { '?' }.joinToString()

  fun generateFilterQuery(nestedSearch: NestedSearch): String {
    val (param, search) = nestedSearch
    val resourceToInclude = search.type
    args.add(baseResourceType.name)
    args.add(param.paramName)
    args.addAll(includeIds)

    var filterQuery = ""
    val filters = search.getFilterQueries()
    val iterator = filters.listIterator()
    while (iterator.hasNext()) {
      iterator.next().let {
        filterQuery += it.query
        args.addAll(it.args)
      }
      if (iterator.hasNext()) {
        filterQuery += if (search.operation == Operation.OR) "\nUNION\n" else "\nINTERSECT\n"
      }
    }
    if (filters.isEmpty()) args.add(resourceToInclude.name)
    return filterQuery
  }

  return forwardIncludes
    .map {
      val (join, order) =
        it.search.getSortOrder(otherTable = "re", groupByColumn = "rie.resourceUuid")
      args.addAll(join.args)
      val filterQuery = generateFilterQuery(it)
      """
      SELECT rie.index_name, rie.resourceUuid, re.serializedResource
      FROM ResourceEntity re
      JOIN ReferenceIndexEntity rie
      ON re.resourceType||"/"||re.resourceId = rie.index_value
      ${join.query}
      WHERE rie.resourceType = ?  AND rie.index_name = ?  AND rie.resourceUuid IN ($uuidsString)
      ${if (filterQuery.isNotBlank()) "AND re.resourceUuid IN ($filterQuery)" else "AND re.resourceType = ?"}
      $order
      """
        .trimIndent()
    }
    .joinToString("\nUNION ALL\n") {
      StringBuilder("SELECT * FROM (\n").append(it.trim()).append("\n)")
    }
    .split("\n")
    .filter { it.isNotBlank() }
    .joinToString("\n") { it.trim() }
    .let { SearchQuery(it, args) }
}

/**
 * Builds the `LEFT JOIN` and `GROUP BY`/`HAVING`/`ORDER BY` fragments needed to sort by the
 * search's sort parameter. Returns the join query (with its args) paired with the order-by clause.
 */
private fun Search.getSortOrder(
  otherTable: String,
  isReferencedSearch: Boolean = false,
  groupByColumn: String = "",
): Pair<SearchQuery, String> {
  var sortJoinStatement = ""
  var sortOrderStatement = ""
  val args = mutableListOf<Any>()
  if (isReferencedSearch && count != null) {
    Logger.e { "count not supported for [rev]include search." }
  }
  sort?.let { sort ->
    val sortTableNames =
      when (sort) {
        is StringClientParam -> listOf(SortTableInfo.STRING_SORT_TABLE_INFO)
        is NumberClientParam -> listOf(SortTableInfo.NUMBER_SORT_TABLE_INFO)
        is DateClientParam ->
          listOf(SortTableInfo.DATE_SORT_TABLE_INFO, SortTableInfo.DATE_TIME_SORT_TABLE_INFO)
        else -> throw NotImplementedError("Unhandled sort parameter of type ${sort::class}: $sort")
      }

    sortJoinStatement =
      sortTableNames
        .mapIndexed { index, sortTableName ->
          val tableAlias = 'b' + index
          """
          LEFT JOIN ${sortTableName.tableName} $tableAlias
          ON $otherTable.resourceUuid = $tableAlias.resourceUuid AND $tableAlias.index_name = ?
          """
        }
        .joinToString(separator = "\n")
    sortTableNames.forEach { _ -> args.add(sort.paramName) }

    sortOrderStatement +=
      generateGroupAndOrderQuery(sort, order!!, otherTable, groupByColumn, sortTableNames)
  }
  return Pair(SearchQuery(sortJoinStatement, args), sortOrderStatement)
}

/**
 * Sorting by a field that has multiple indexed values may result in duplicated resources. So, we use
 * `GROUP BY` + `HAVING` clause to find distinct values in specified order.
 *
 * To make the sorting order a bit predictable, we use MIN and MAX functions with `HAVING` to use the
 * corresponding values for GROUPING to find the distinct results.
 *
 * e.g. If there are Two Patients resources with multiple first names P1 ( first names =`3`, `1`) and
 * P2 (first names = `2`, `4`), when sorting them in
 *
 * *ASCENDING order*: MIN function is used so that the smallest names of both the patients are
 * considered for Grouping `[P1(`1`), P2(`2`)]`.
 *
 * *DESCENDING order*: MAX function is used so that the largest names of both the patients are
 * considered for Grouping `[P2(`4`), P1(`3`)]`.
 *
 * For the special case where the index value is NULL, we use the default 0 value and to complete the
 * expression, we check that the value is greater than [MIN_VALUE], the minimum value an INTEGER type
 * can store in SQLITE. The reason to check against [MIN_VALUE] rather that 0, since string is always
 * greater than integer (StringIndexEntity) and Date/DateTimeIndexEntity will always have positive
 * integer values, is because the NumberIndexEntity table may contain negative values in it.
 *
 * Without the `>= MIN_VALUE` check, NULL values are not included in the results if the default is 0.
 *
 * The default values provided in GROUP BY stage are not carried forward during the ORDER BY, so we
 * provide [MAX_VALUE] and [MIN_VALUE] as default in ORDER BY respectively for ASCENDING and
 * DESCENDING to make sure that results with null index values are always at the bottom.
 */
private fun generateGroupAndOrderQuery(
  sort: ClientParam,
  order: Order,
  otherTable: String,
  groupByColumn: String,
  sortTableNames: List<SortTableInfo>,
): String {
  var sortOrderStatement = ""
  val havingColumn =
    when (sort) {
      is StringClientParam,
      is NumberClientParam, -> "IFNULL(b.index_value,0)"
      is DateClientParam -> "IFNULL(b.index_from,0) + IFNULL(c.index_from,0)"
      else -> throw NotImplementedError("Unhandled sort parameter of type ${sort::class}: $sort")
    }

  sortOrderStatement +=
    """
    GROUP BY $otherTable.resourceUuid ${if (groupByColumn.isNotEmpty()) ", $groupByColumn" else ""}
    HAVING ${if (order == Order.ASCENDING) "MIN($havingColumn) >= $MIN_VALUE" else "MAX($havingColumn) >= $MIN_VALUE"}

    """
      .trimIndent()
  val defaultValue = if (order == Order.ASCENDING) MAX_VALUE else MIN_VALUE
  sortTableNames.forEachIndexed { index, sortTableName ->
    val tableAlias = 'b' + index
    sortOrderStatement +=
      if (index == 0) {
        """
        ORDER BY IFNULL($tableAlias.${sortTableName.columnName}, $defaultValue) ${order.sqlString}
        """
          .trimIndent()
      } else {
        ", IFNULL($tableAlias.${SortTableInfo.DATE_TIME_SORT_TABLE_INFO.columnName}, $defaultValue) ${order.sqlString}"
      }
  }
  return sortOrderStatement
}

/** Maps every filter criterion (string, quantity, number, reference, date/time, token, uri) to its subquery. */
private fun Search.getFilterQueries() =
  (stringFilterCriteria +
      quantityFilterCriteria +
      numberFilterCriteria +
      referenceFilterCriteria +
      dateTimeFilterCriteria +
      tokenFilterCriteria +
      uriFilterCriteria)
    .map { it.query(type) }

/**
 * Builds the main SQL [SearchQuery], combining sort, filter, nested, and limit clauses. Produces a
 * `COUNT(*)` query when [isCount] is set, a nested sub-select when [nestedContext] is provided (for
 * chained searches), or a normal resource query otherwise.
 */
internal fun Search.getQuery(
  isCount: Boolean = false,
  nestedContext: NestedContext? = null,
): SearchQuery {
  val (join, order) = getSortOrder(otherTable = "a")
  val sortJoinStatement = join.query
  val sortOrderStatement = order
  val sortArgs = join.args

  val filterQuery = getFilterQueries()
  val filterQueryStatement =
    filterQuery.joinToString(separator = "${operation.logicalOperator} ") {
      """
      a.resourceUuid IN (
      ${it.query}
      )

            """
        .trimIndent()
    }
  val filterQueryArgs = filterQuery.flatMap { it.args }

  var limitStatement = ""
  val limitArgs = mutableListOf<Any>()
  if (count != null) {
    limitStatement = "LIMIT ?"
    limitArgs += count!!
    if (from != null) {
      limitStatement += " OFFSET ?"
      limitArgs += from!!
    }
  }

  val nestedFilterQuery = nestedSearches.nestedQuery(type, operation)
  val nestedQueryFilterStatement = nestedFilterQuery?.query ?: ""
  val nestedQueryFilterArgs = nestedFilterQuery?.args ?: emptyList()

  val filterStatement =
    listOf(filterQueryStatement, nestedQueryFilterStatement)
      .filter { it.isNotBlank() }
      .joinToString(separator = " AND ")
      .ifBlank { "a.resourceType = ?" }
  val filterArgs = (filterQueryArgs + nestedQueryFilterArgs).ifEmpty { listOf(type.name) }

  val whereArgs = mutableListOf<Any>()
  val nestedArgs = mutableListOf<Any>()
  val query =
    when {
        isCount -> {
          """
          SELECT COUNT(*)
          FROM ResourceEntity a
          $sortJoinStatement
          WHERE $filterStatement
          $sortOrderStatement
          $limitStatement
          """
        }
        nestedContext != null -> {
          whereArgs.add(nestedContext.param.paramName)
          val start = "${nestedContext.parentType.name}/".length + 1
          nestedArgs.add(nestedContext.parentType.name)
          """
          SELECT resourceUuid
          FROM ResourceEntity a
          WHERE a.resourceType = ? AND a.resourceId IN (
          SELECT substr(a.index_value, $start)
          FROM ReferenceIndexEntity a
          $sortJoinStatement
          WHERE a.index_name = ? AND $filterStatement
          $sortOrderStatement
          $limitStatement)
          """
        }
        else ->
          """
          SELECT a.resourceUuid, a.serializedResource
          FROM ResourceEntity a
          $sortJoinStatement
          WHERE $filterStatement
          $sortOrderStatement
          $limitStatement
          """
      }
      .split("\n")
      .filter { it.isNotBlank() }
      .joinToString("\n") { it.trim() }

  return SearchQuery(
    query,
    nestedArgs + sortArgs + whereArgs + filterArgs + limitArgs,
  )
}

/** The SQL keyword for this sort order (`ASC`, `DESC`, or empty when null). */
private val Order?.sqlString: String
  get() =
    when (this) {
      Order.ASCENDING -> "ASC"
      Order.DESCENDING -> "DESC"
      null -> ""
    }

/**
 * Returns the SQL condition and bound params for a date [value] filtered with [prefix], over the
 * date index's `index_from`/`index_to` epoch-day range. See https://www.hl7.org/fhir/search.html#date.
 */
internal fun getConditionParamPairForDate(
  prefix: ParamPrefixEnum,
  value: FhirDate,
): ConditionParam<Long> {
  val (start, end) = fhirDateToEpochDayRange(value)
  return when (prefix) {
    ParamPrefixEnum.APPROXIMATE -> {
      val now = Clock.System.now().toEpochMilliseconds()
      val nowDay = now / 86400000L
      val currentRange = nowDay to nowDay
      val (diffStart, diffEnd) =
        getApproximateDateRange(start..end, currentRange.first..currentRange.second)
      ConditionParam(
        "index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?",
        diffStart,
        diffEnd,
        diffStart,
        diffEnd,
      )
    }
    ParamPrefixEnum.STARTS_AFTER -> ConditionParam("index_from > ?", end)
    ParamPrefixEnum.ENDS_BEFORE -> ConditionParam("index_to < ?", start)
    ParamPrefixEnum.NOT_EQUAL ->
      ConditionParam(
        "index_from NOT BETWEEN ? AND ? OR index_to NOT BETWEEN ? AND ?",
        start,
        end,
        start,
        end,
      )
    ParamPrefixEnum.EQUAL ->
      ConditionParam(
        "index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?",
        start,
        end,
        start,
        end,
      )
    ParamPrefixEnum.GREATERTHAN -> ConditionParam("index_to > ?", end)
    ParamPrefixEnum.GREATERTHAN_OR_EQUALS -> ConditionParam("index_to >= ?", start)
    ParamPrefixEnum.LESSTHAN -> ConditionParam("index_from < ?", start)
    ParamPrefixEnum.LESSTHAN_OR_EQUALS -> ConditionParam("index_from <= ?", end)
  }
}

/**
 * Returns the SQL condition and bound params for a dateTime [value] filtered with [prefix], over the
 * dateTime index's `index_from`/`index_to` epoch-millis range. See https://www.hl7.org/fhir/search.html#date.
 */
internal fun getConditionParamPairForDateTime(
  prefix: ParamPrefixEnum,
  value: FhirDateTime,
): ConditionParam<Long> {
  val (start, end) = fhirDateTimeToEpochMillisRange(value)
  return when (prefix) {
    ParamPrefixEnum.APPROXIMATE -> {
      val nowMs = Clock.System.now().toEpochMilliseconds()
      val (diffStart, diffEnd) = getApproximateDateRange(start..end, nowMs..nowMs)
      ConditionParam(
        "index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?",
        diffStart,
        diffEnd,
        diffStart,
        diffEnd,
      )
    }
    ParamPrefixEnum.STARTS_AFTER -> ConditionParam("index_from > ?", end)
    ParamPrefixEnum.ENDS_BEFORE -> ConditionParam("index_to < ?", start)
    ParamPrefixEnum.NOT_EQUAL ->
      ConditionParam(
        "index_from NOT BETWEEN ? AND ? OR index_to NOT BETWEEN ? AND ?",
        start,
        end,
        start,
        end,
      )
    ParamPrefixEnum.EQUAL ->
      ConditionParam(
        "index_from BETWEEN ? AND ? AND index_to BETWEEN ? AND ?",
        start,
        end,
        start,
        end,
      )
    ParamPrefixEnum.GREATERTHAN -> ConditionParam("index_to > ?", end)
    ParamPrefixEnum.GREATERTHAN_OR_EQUALS -> ConditionParam("index_to >= ?", start)
    ParamPrefixEnum.LESSTHAN -> ConditionParam("index_from < ?", start)
    ParamPrefixEnum.LESSTHAN_OR_EQUALS -> ConditionParam("index_from <= ?", end)
  }
}

/**
 * Returns the condition and list of params required in NumberFilter.query see
 * https://www.hl7.org/fhir/search.html#number.
 */
internal fun getConditionParamPair(
  prefix: ParamPrefixEnum?,
  value: BigDecimal,
): ConditionParam<Double> {
  require(
    (value.precision - 1 - value.exponent) > 0 ||
      (prefix != ParamPrefixEnum.STARTS_AFTER && prefix != ParamPrefixEnum.ENDS_BEFORE),
  ) {
    "Prefix $prefix not allowed for Integer type"
  }
  return when (prefix) {
    ParamPrefixEnum.EQUAL,
    null, -> {
      val precision = value.getRange()
      ConditionParam(
        "index_value >= ? AND index_value < ?",
        (value - precision).doubleValue(false),
        (value + precision).doubleValue(false),
      )
    }
    ParamPrefixEnum.GREATERTHAN -> ConditionParam("index_value > ?", value.doubleValue(false))
    ParamPrefixEnum.GREATERTHAN_OR_EQUALS ->
      ConditionParam("index_value >= ?", value.doubleValue(false))
    ParamPrefixEnum.LESSTHAN -> ConditionParam("index_value < ?", value.doubleValue(false))
    ParamPrefixEnum.LESSTHAN_OR_EQUALS ->
      ConditionParam("index_value <= ?", value.doubleValue(false))
    ParamPrefixEnum.NOT_EQUAL -> {
      val precision = value.getRange()
      ConditionParam(
        "index_value < ? OR index_value >= ?",
        (value - precision).doubleValue(false),
        (value + precision).doubleValue(false),
      )
    }
    ParamPrefixEnum.ENDS_BEFORE -> ConditionParam("index_value < ?", value.doubleValue(false))
    ParamPrefixEnum.STARTS_AFTER -> ConditionParam("index_value > ?", value.doubleValue(false))
    ParamPrefixEnum.APPROXIMATE -> {
      val range = value.multiply(BigDecimal.fromDouble(APPROXIMATION_COEFFICIENT))
      ConditionParam(
        "index_value >= ? AND index_value <= ?",
        (value - range).doubleValue(false),
        (value + range).doubleValue(false),
      )
    }
  }
}

/**
 * Returns the condition and list of params required in Quantity.query see
 * https://www.hl7.org/fhir/search.html#quantity.
 */
internal fun getConditionParamPair(
  prefix: ParamPrefixEnum?,
  value: BigDecimal,
  system: String?,
  unit: String?,
): ConditionParam<Any> {
  var canonicalizedUnit = unit
  var canonicalizedValue = value

  if (system == ucumUrl && unit != null) {
    val ucumValue = UcumValue(unit, value).toEqualCanonical()
    canonicalizedUnit = ucumValue.code
    canonicalizedValue = ucumValue.value
  }

  val queryBuilder = StringBuilder()
  val argList = mutableListOf<Any>()

  if (system != null) {
    queryBuilder.append("index_system = ? AND ")
    argList.add(system)
  }

  if (canonicalizedUnit != null) {
    queryBuilder.append("index_code = ? AND ")
    argList.add(canonicalizedUnit)
  }

  val valueConditionParam = getConditionParamPair(prefix, canonicalizedValue)
  queryBuilder.append(valueConditionParam.condition)
  argList.addAll(valueConditionParam.params)

  return ConditionParam(queryBuilder.toString(), argList)
}

/**
 * Returns the range in which the value should lie for it to be considered a match (@see
 * NumberFilter.query). The value is directly related to the scale of the BigDecimal.
 *
 * For example, a search with a value 100.00 (has a scale of 2) would match any value in [99.995,
 * 100.005) and the function returns 0.005.
 *
 * For Big integers which have a negative scale the function returns 5 For example A search with a
 * value 1000 would match any value in [995, 1005) and the function returns 5.
 *
 * The original used Java's `BigDecimal.scale()`, which isn't available on the KMP (ionspin)
 * `BigDecimal`, so we derive the scale (number of decimal places) from `precision` and `exponent`
 * instead.
 */
private fun BigDecimal.getRange(): BigDecimal {
  val decimalPlaces = precision - 1 - exponent
  return if (decimalPlaces >= 0) {
    BigDecimal.fromDouble(0.5).divide(BigDecimal.fromInt(10).pow(decimalPlaces))
  } else {
    BigDecimal.fromInt(5)
  }
}

/** A SQL condition fragment paired with its bound [params]. */
data class ConditionParam<T>(val condition: String, val params: List<T>) {
  constructor(condition: String, vararg params: T) : this(condition, params.asList())

  val queryString = if (params.size > 1) "($condition)" else condition
}

/** Maps each sortable search-param type to the index table and column used to sort on it. */
private enum class SortTableInfo(val tableName: String, val columnName: String) {
  STRING_SORT_TABLE_INFO("StringIndexEntity", "index_value"),
  NUMBER_SORT_TABLE_INFO("NumberIndexEntity", "index_value"),
  DATE_SORT_TABLE_INFO("DateIndexEntity", "index_from"),
  DATE_TIME_SORT_TABLE_INFO("DateTimeIndexEntity", "index_from"),
}

/**
 * Computes the matching range for the `ap` (approximate) date/dateTime prefix by widening
 * [valueRange] proportionally to its distance from [currentRange] (now). See
 * https://www.hl7.org/fhir/search.html#prefix.
 */
private fun getApproximateDateRange(
  valueRange: LongRange,
  currentRange: LongRange,
  approximationCoefficient: Double = APPROXIMATION_COEFFICIENT,
): ApproximateDateRange {
  return ApproximateDateRange(
    (valueRange.first -
        approximationCoefficient * (valueRange.first - currentRange.first).absoluteValue)
      .roundToLong(),
    (valueRange.last +
        approximationCoefficient * (valueRange.last - currentRange.last).absoluteValue)
      .roundToLong(),
  )
}

/** The widened [start]..[end] bounds produced for an approximate (`ap`) date search. */
private data class ApproximateDateRange(val start: Long, val end: Long)

/** Converts a [FhirDate] to its inclusive `[start, end]` epoch-day range (a year or month spans multiple days). */
internal fun fhirDateToEpochDayRange(date: FhirDate): Pair<Long, Long> =
  when (date) {
    is FhirDate.Date -> {
      val epochDay = date.date.toEpochDays()
      epochDay to epochDay
    }
    is FhirDate.YearMonth -> {
      val firstDay = LocalDate(date.value.year, date.value.month, 1)
      val nextMonth = firstDay.plus(1, DateTimeUnit.MONTH)
      firstDay.toEpochDays() to (nextMonth.toEpochDays() - 1)
    }
    is FhirDate.Year -> {
      val firstDay = LocalDate(date.value, 1, 1)
      val nextYear = LocalDate(date.value + 1, 1, 1)
      firstDay.toEpochDays() to (nextYear.toEpochDays() - 1)
    }
  }

/** Returns the start of [dateTime] as epoch milliseconds (UTC). */
internal fun fhirDateTimeToEpochMillis(dateTime: FhirDateTime): Long =
  when (dateTime) {
    is FhirDateTime.DateTime ->
      dateTime.dateTime.toInstant(dateTime.utcOffset).toEpochMilliseconds()
    is FhirDateTime.Date -> dateTime.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    is FhirDateTime.YearMonth -> {
      val firstDay = LocalDate(dateTime.value.year, dateTime.value.month, 1)
      firstDay.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    is FhirDateTime.Year -> {
      val firstDay = LocalDate(dateTime.value, 1, 1)
      firstDay.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
  }

/** Returns the end of [dateTime] as epoch milliseconds (UTC) — the last instant of its precision window. */
internal fun fhirDateTimeToEndEpochMillis(dateTime: FhirDateTime): Long =
  when (dateTime) {
    is FhirDateTime.DateTime ->
      dateTime.dateTime.toInstant(dateTime.utcOffset).toEpochMilliseconds()
    is FhirDateTime.Date -> {
      val nextDay = dateTime.date.plus(1, DateTimeUnit.DAY)
      nextDay.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() - 1
    }
    is FhirDateTime.YearMonth -> {
      val firstDay = LocalDate(dateTime.value.year, dateTime.value.month, 1)
      val nextMonth = firstDay.plus(1, DateTimeUnit.MONTH)
      nextMonth.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() - 1
    }
    is FhirDateTime.Year -> {
      val nextYear = LocalDate(dateTime.value + 1, 1, 1)
      nextYear.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() - 1
    }
  }

/** Returns the inclusive `[start, end]` epoch-millis range covered by [dateTime]'s precision. */
internal fun fhirDateTimeToEpochMillisRange(dateTime: FhirDateTime): Pair<Long, Long> =
  fhirDateTimeToEpochMillis(dateTime) to fhirDateTimeToEndEpochMillis(dateTime)
