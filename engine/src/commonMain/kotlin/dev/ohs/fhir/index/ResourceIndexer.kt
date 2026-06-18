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

import dev.ohs.fhir.UcumValue
import dev.ohs.fhir.toEqualCanonical
import dev.ohs.fhir.getResourceType
import dev.ohs.fhir.index.entities.DateIndex
import dev.ohs.fhir.index.entities.DateTimeIndex
import dev.ohs.fhir.index.entities.NumberIndex
import dev.ohs.fhir.index.entities.PositionIndex
import dev.ohs.fhir.index.entities.QuantityIndex
import dev.ohs.fhir.index.entities.ReferenceIndex
import dev.ohs.fhir.index.entities.StringIndex
import dev.ohs.fhir.index.entities.TokenIndex
import dev.ohs.fhir.index.entities.UriIndex
import dev.ohs.fhir.search.LAST_UPDATED
import dev.ohs.fhir.search.LOCAL_LAST_UPDATED
import dev.ohs.fhir.ucumUrl
import dev.ohs.fhir.fhirpath.FhirPathEngine
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Instant
import dev.ohs.fhir.model.r4.Integer
import dev.ohs.fhir.model.r4.Location
import dev.ohs.fhir.model.r4.Money
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Timing
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * Indexes a FHIR resource according to the
 * [search parameters](https://www.hl7.org/fhir/searchparameter-registry.html).
 */
internal class ResourceIndexer(
  private val searchParamDefinitionsProvider: SearchParamDefinitionsProvider,
) {
  private val fhirPathEngine = FhirPathEngine.forR4()

  fun <R : Resource> index(resource: R) = extractIndexValues(resource)

  private fun <R : Resource> extractIndexValues(resource: R): ResourceIndices {
    val resourceType = getResourceType(resource::class)
    val indexBuilder = ResourceIndices.Builder(resourceType, resource.id.orEmpty())
    searchParamDefinitionsProvider
      .get(resource)
      .flatMap { searchParam ->
        // Some search params reference FHIRPath functions kotlin-fhir-path doesn't implement yet
        // (e.g. `resolve()` in `Encounter.subject.where(resolve() is Patient)`). A single
        // unsupported expression shouldn't break indexing for the whole resource, so we swallow
        // the exception and skip that param.
        runCatching { fhirPathEngine.evaluateExpression(searchParam.path, resource) }
          .getOrDefault(emptyList())
          .map { searchParam to it }
      }
      .forEach { (searchParam, value) ->
        when (searchParam.type) {
          SearchParamType.NUMBER ->
            numberIndex(searchParam, value)?.also { indexBuilder.addNumberIndex(it) }
          SearchParamType.DATE ->
            if (value is Date) {
              dateIndex(searchParam, value)?.also { indexBuilder.addDateIndex(it) }
            } else {
              dateTimeIndex(searchParam, value)?.also { indexBuilder.addDateTimeIndex(it) }
            }
          SearchParamType.STRING ->
            stringIndex(searchParam, value)?.also { indexBuilder.addStringIndex(it) }
          SearchParamType.TOKEN ->
            tokenIndex(searchParam, value).forEach { indexBuilder.addTokenIndex(it) }
          SearchParamType.REFERENCE ->
            referenceIndex(searchParam, value)?.also { indexBuilder.addReferenceIndex(it) }
          SearchParamType.QUANTITY ->
            quantityIndex(searchParam, value).forEach { indexBuilder.addQuantityIndex(it) }
          SearchParamType.URI -> uriIndex(searchParam, value)?.also { indexBuilder.addUriIndex(it) }
          SearchParamType.SPECIAL ->
            specialIndex(value)?.also { indexBuilder.addPositionIndex(it) }
          // TODO: Handle composite type https://github.com/google/android-fhir/issues/292.
          else -> Unit
        }
      }
    return indexBuilder.build()
  }

  companion object {

    /**
     * The FHIR currency code system. See: https://bit.ly/30YB3ML. See:
     * https://www.hl7.org/fhir/valueset-currencies.html.
     */
    private const val FHIR_CURRENCY_CODE_SYSTEM = "urn:iso:std:iso:4217"

    private fun numberIndex(searchParam: SearchParamDefinition, value: Any): NumberIndex? =
      when (value) {
        // fhir-path's evaluateExpression unwraps Integer/UnsignedInt/PositiveInt to kotlin.Int
        // and Decimal to BigDecimal before returning. Handle those first.
        is Int -> NumberIndex(searchParam.name, searchParam.path, BigDecimal.fromInt(value))
        is BigDecimal -> NumberIndex(searchParam.name, searchParam.path, value)
        // Defensive fallback in case a kotlin-fhir wrapper leaks through unconverted.
        is Integer ->
          value.value?.let { NumberIndex(searchParam.name, searchParam.path, BigDecimal.fromInt(it)) }
        is Decimal -> value.value?.let { NumberIndex(searchParam.name, searchParam.path, it) }
        else -> null
      }

    private fun dateIndex(searchParam: SearchParamDefinition, value: Date): DateIndex? {
      val fhirDate = value.value ?: return null
      val (from, to) = fhirDateToEpochDaysRange(fhirDate)
      return DateIndex(searchParam.name, searchParam.path, from, to)
    }

    private fun dateTimeIndex(searchParam: SearchParamDefinition, value: Any): DateTimeIndex? =
      when (value) {
        is DateTime -> {
          val dt = value.value ?: return null
          val (from, to) = fhirDateTimeToEpochMillisRange(dt)
          DateTimeIndex(searchParam.name, searchParam.path, from, to)
        }
        is Instant -> {
          // An instant is zero-width.
          val dt = value.value ?: return null
          val millis = fhirDateTimeToEpochMillis(dt)
          DateTimeIndex(searchParam.name, searchParam.path, millis, millis)
        }
        is Period -> {
          val from = value.start?.value?.let { fhirDateTimeToEpochMillis(it) } ?: 0L
          val to = value.end?.value?.let { fhirDateTimeToEndEpochMillis(it) } ?: Long.MAX_VALUE
          DateTimeIndex(searchParam.name, searchParam.path, from, to)
        }
        is Timing -> {
          val events = value.event.mapNotNull { it.value }
          if (events.isEmpty()) {
            null
          } else {
            DateTimeIndex(
              searchParam.name,
              searchParam.path,
              events.minOf { fhirDateTimeToEpochMillis(it) },
              events.maxOf { fhirDateTimeToEndEpochMillis(it) },
            )
          }
        }
        is dev.ohs.fhir.model.r4.String -> {
          // e.g. CarePlan may have schedule as a string value 2011-06-27T09:30:10+01:00
          // OR 'daily'. Only the former is parseable as a date-time.
          value.value?.let { str ->
            runCatching { FhirDateTime.fromString(str) }.getOrNull()?.let { dt ->
              val (from, to) = fhirDateTimeToEpochMillisRange(dt)
              DateTimeIndex(searchParam.name, searchParam.path, from, to)
            }
          }
        }
        else -> null
      }

    /**
     * Extension to express [HumanName] as a separated string using [separator]. See
     * https://www.hl7.org/fhir/patient.html#search
     */
    private fun HumanName.asString(separator: CharSequence = " "): kotlin.String {
      val parts = buildList<kotlin.String> {
        prefix.forEach { it.value?.let(::add) }
        given.forEach { it.value?.let(::add) }
        family?.value?.let(::add)
        suffix.forEach { it.value?.let(::add) }
        text?.value?.let(::add)
      }
      return parts.filter { it.isNotBlank() }.joinToString(separator)
    }

    /**
     * Extension to express [Address] as a string using [separator]. See
     * https://www.hl7.org/fhir/patient.html#search
     */
    private fun Address.asString(separator: CharSequence = ", "): kotlin.String {
      val parts = buildList<kotlin.String> {
        line.forEach { it.value?.let(::add) }
        city?.value?.let(::add)
        district?.value?.let(::add)
        state?.value?.let(::add)
        country?.value?.let(::add)
        postalCode?.value?.let(::add)
        text?.value?.let(::add)
      }
      return parts.filter { it.isNotBlank() }.joinToString(separator)
    }

    private fun stringIndex(searchParam: SearchParamDefinition, value: Any): StringIndex? =
      when (value) {
        is HumanName -> {
          val str = value.asString()
          if (str.isNotBlank()) StringIndex(searchParam.name, searchParam.path, str) else null
        }
        is Address -> {
          val str = value.asString()
          if (str.isNotBlank()) StringIndex(searchParam.name, searchParam.path, str) else null
        }
        // fhir-path unwraps String/Uri/Code/Id/Markdown/Oid/Uuid/Base64Binary to kotlin.String.
        is kotlin.String -> {
          if (value.isNotBlank()) StringIndex(searchParam.name, searchParam.path, value) else null
        }
        // Defensive fallback for kotlin-fhir wrapper.
        is dev.ohs.fhir.model.r4.String -> {
          value.value?.takeIf { it.isNotBlank() }?.let {
            StringIndex(searchParam.name, searchParam.path, it)
          }
        }
        else -> null
      }

    private fun tokenIndex(searchParam: SearchParamDefinition, value: Any): List<TokenIndex> =
      when (value) {
        // fhir-path unwraps Boolean to kotlin.Boolean, and String/Uri/Code/Id/Markdown/Oid/Uuid/
        // Base64Binary all to kotlin.String. Handle those first.
        is kotlin.Boolean ->
          listOf(TokenIndex(searchParam.name, searchParam.path, system = null, value.toString()))
        is kotlin.String ->
          if (value.isEmpty()) {
            emptyList()
          } else {
            listOf(TokenIndex(searchParam.name, searchParam.path, system = null, value))
          }
        // Complex types are not unwrapped by fhir-path.
        is Identifier ->
          value.value?.value?.let { idValue ->
            listOf(
              TokenIndex(
                searchParam.name,
                searchParam.path,
                value.system?.value,
                idValue,
              ),
            )
          } ?: emptyList()
        is CodeableConcept ->
          value.coding.mapNotNull { coding ->
            val codeStr = coding.code?.value ?: return@mapNotNull null
            if (codeStr.isEmpty()) return@mapNotNull null
            TokenIndex(searchParam.name, searchParam.path, coding.system?.value ?: "", codeStr)
          }
        is Coding ->
          value.code?.value?.takeIf { it.isNotEmpty() }?.let { codeStr ->
            listOf(
              TokenIndex(
                searchParam.name,
                searchParam.path,
                value.system?.value ?: "",
                codeStr,
              ),
            )
          } ?: emptyList()
        // Defensive fallbacks for kotlin-fhir wrappers that might leak through unconverted.
        is dev.ohs.fhir.model.r4.Boolean ->
          value.value?.let {
            listOf(TokenIndex(searchParam.name, searchParam.path, system = null, it.toString()))
          } ?: emptyList()
        is Code ->
          value.value?.let {
            listOf(TokenIndex(searchParam.name, searchParam.path, system = "", it))
          } ?: emptyList()
        is Id ->
          value.value?.let {
            listOf(TokenIndex(searchParam.name, searchParam.path, system = null, it))
          } ?: emptyList()
        else -> emptyList()
      }

    private fun referenceIndex(searchParam: SearchParamDefinition, value: Any): ReferenceIndex? {
      val ref: kotlin.String? =
        when (value) {
          // fhir-path unwraps Uri / Canonical to kotlin.String.
          is kotlin.String -> value
          is Reference -> value.reference?.value
          // Defensive fallback for kotlin-fhir wrappers.
          is Canonical -> value.value
          is Uri -> value.value
          else -> throw UnsupportedOperationException("Value $value is not readable by SDK")
        }
      return ref?.takeIf { it.isNotEmpty() }?.let {
        ReferenceIndex(searchParam.name, searchParam.path, it)
      }
    }

    private fun quantityIndex(
      searchParam: SearchParamDefinition,
      value: Any,
    ): List<QuantityIndex> =
      when (value) {
        is Money -> {
          val amount = value.value?.value
          val currency = value.currency?.value?.name
          if (amount != null && currency != null) {
            listOf(
              QuantityIndex(
                searchParam.name,
                searchParam.path,
                FHIR_CURRENCY_CODE_SYSTEM,
                currency,
                amount,
              ),
            )
          } else {
            emptyList()
          }
        }
        is Quantity -> {
          val quantityIndices = mutableListOf<QuantityIndex>()
          val numericValue = value.value?.value ?: return emptyList()

          // Add quantity indexing record for the human-readable unit.
          val unit = value.unit?.value
          if (unit != null) {
            quantityIndices.add(
              QuantityIndex(searchParam.name, searchParam.path, "", unit, numericValue),
            )
          }

          // Add quantity indexing record for the coded unit (canonicalised if UCUM).
          var canonicalCode = value.code?.value
          var canonicalValue = numericValue
          val systemUri = value.system?.value
          if (systemUri == ucumUrl && canonicalCode != null) {
            val ucumUnit = UcumValue(canonicalCode, numericValue).toEqualCanonical()
            canonicalCode = ucumUnit.code
            canonicalValue = ucumUnit.value
          }
          quantityIndices.add(
            QuantityIndex(
              searchParam.name,
              searchParam.path,
              systemUri ?: "",
              canonicalCode ?: "",
              canonicalValue,
            ),
          )
          quantityIndices
        }
        else -> emptyList()
      }

    private fun uriIndex(searchParam: SearchParamDefinition, value: Any): UriIndex? {
      // fhir-path unwraps Uri / Canonical to kotlin.String. Defensive fallback to Uri wrapper.
      val uri =
        when (value) {
          is kotlin.String -> value
          is Uri -> value.value
          else -> null
        }
      return if (!uri.isNullOrEmpty()) UriIndex(searchParam.name, searchParam.path, uri) else null
    }

    private fun specialIndex(value: Any): PositionIndex? {
      if (value !is Location.Position) return null
      val lat = value.latitude.value?.doubleValue(exactRequired = false) ?: return null
      val lon = value.longitude.value?.doubleValue(exactRequired = false) ?: return null
      return PositionIndex(lat, lon)
    }

    // TODO: Date / DateTime / Time / Quantity primitive indexing is blocked until a new fhir-path
    //   release exposes FhirPathDate / FhirPathDateTime / FhirPathTime / FhirPathQuantity as public
    //   (they're `internal` today). After the release, add `is FhirPath*` branches to
    //   dateIndex/dateTimeIndex/quantityIndex.

    /** Returns the [start, end] epoch-day range for a [FhirDate], inclusive on both ends. */
    private fun fhirDateToEpochDaysRange(fhirDate: FhirDate): Pair<Long, Long> =
      when (fhirDate) {
        is FhirDate.Year -> {
          val start = LocalDate(fhirDate.value, 1, 1).toEpochDays()
          val end = LocalDate(fhirDate.value, 12, 31).toEpochDays()
          start to end
        }
        is FhirDate.YearMonth -> {
          val firstDay = LocalDate(fhirDate.value.year, fhirDate.value.month, 1)
          val lastDay = firstDay.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
          firstDay.toEpochDays() to lastDay.toEpochDays()
        }
        is FhirDate.Date -> fhirDate.date.toEpochDays().let { it to it }
      }

    /** Returns the [start, end] epoch-millis range for a [FhirDateTime], inclusive on both ends. */
    private fun fhirDateTimeToEpochMillisRange(dt: FhirDateTime): Pair<Long, Long> =
      fhirDateTimeToEpochMillis(dt) to fhirDateTimeToEndEpochMillis(dt)

    /** Start-of-period epoch millis for a [FhirDateTime] at its native precision. */
    private fun fhirDateTimeToEpochMillis(dt: FhirDateTime): Long =
      when (dt) {
        is FhirDateTime.Year ->
          LocalDateTime(dt.value, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        is FhirDateTime.YearMonth -> {
          val ym = dt.value
          LocalDateTime(ym.year, ym.month, 1, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        }
        is FhirDateTime.Date ->
          LocalDateTime(dt.date.year, dt.date.month, dt.date.day, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        is FhirDateTime.DateTime -> dt.dateTime.toInstant(dt.utcOffset).toEpochMilliseconds()
      }

    /** Inclusive end-of-period epoch millis for a [FhirDateTime] at its native precision. */
    private fun fhirDateTimeToEndEpochMillis(dt: FhirDateTime): Long =
      when (dt) {
        is FhirDateTime.Year ->
          LocalDateTime(dt.value + 1, 1, 1, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds() - 1
        is FhirDateTime.YearMonth -> {
          val firstDay = LocalDate(dt.value.year, dt.value.month, 1)
          val firstOfNextMonth = firstDay.plus(1, DateTimeUnit.MONTH)
          LocalDateTime(firstOfNextMonth.year, firstOfNextMonth.month, firstOfNextMonth.day, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds() - 1
        }
        is FhirDateTime.Date -> {
          val nextDay = dt.date.plus(1, DateTimeUnit.DAY)
          LocalDateTime(nextDay.year, nextDay.month, nextDay.day, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds() - 1
        }
        is FhirDateTime.DateTime ->
          // kotlin-fhir's DateTime variant doesn't expose precision separately. Treat as
          // second-precision (matches the typical FHIR profile and HAPI's `add(value, 1).time - 1`).
          dt.dateTime
            .toInstant(dt.utcOffset)
            .plus(1, DateTimeUnit.SECOND)
            .toEpochMilliseconds() - 1
      }

    fun createLastUpdatedIndex(resourceType: ResourceType, instant: Instant): DateTimeIndex {
      val epochMillis = instant.toEpochMillis()
      return DateTimeIndex(
        name = LAST_UPDATED,
        path = arrayOf(resourceType.name, "meta", "lastUpdated").joinToString(separator = "."),
        from = epochMillis,
        to = epochMillis,
      )
    }

    fun createLocalLastUpdatedIndex(resourceType: ResourceType, instant: Instant): DateTimeIndex {
      val epochMillis = instant.toEpochMillis()
      return DateTimeIndex(
        name = LOCAL_LAST_UPDATED,
        path = arrayOf(resourceType.name, "meta", "localLastUpdated").joinToString(separator = "."),
        from = epochMillis,
        to = epochMillis,
      )
    }

    private fun Instant.toEpochMillis(): Long {
      val dt =
        value as? FhirDateTime.DateTime
          ?: error("createLastUpdatedIndex requires a DateTime FhirDateTime value")
      return kotlin.time.Instant.parse("${dt.dateTime}${dt.utcOffset}").toEpochMilliseconds()
    }
  }
}
