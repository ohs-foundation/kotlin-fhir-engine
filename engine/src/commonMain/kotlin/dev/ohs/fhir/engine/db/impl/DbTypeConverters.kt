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
package dev.ohs.fhir.engine.db.impl

import androidx.room3.ColumnTypeConverter
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Type converters for Room to persist ResourceType as a string. see:
 * https://developer.android.com/training/data-storage/room/referencing-data
 */
internal object DbTypeConverters {
  /**
   * Since we're narrowing BigDecimal to double, search/sort precision is limited. Search/sort for
   * values that are close enough to resolve to the same double will be undefined.
   */
  @ColumnTypeConverter fun bigDecimalToDouble(value: BigDecimal): Double = value.doubleValue(false)

  @ColumnTypeConverter
  fun doubleToBigDecimal(value: Double): BigDecimal = BigDecimal.fromDouble(value)

  @ColumnTypeConverter fun uuidToString(uuid: Uuid?): String? = uuid?.toString()

  @ColumnTypeConverter fun stringToUuid(value: String?): Uuid? = value?.let(Uuid::parse)

  @ColumnTypeConverter
  fun instantToEpochMillis(instant: Instant?): Long? = instant?.toEpochMilliseconds()

  @ColumnTypeConverter
  fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

  /**
   * Converts a [ResourceType] into a String to be persisted in the database. This allows us to save
   * [ResourceType] into the database while keeping it as the real type in entities.
   */
  @ColumnTypeConverter fun resourceTypeToString(type: ResourceType?): String? = type?.name

  /** Converts a String into a [ResourceType]. Called when a query returns a [ResourceType]. */
  @ColumnTypeConverter
  fun stringToResourceType(value: String?): ResourceType? = value?.let(ResourceType::valueOf)
}
