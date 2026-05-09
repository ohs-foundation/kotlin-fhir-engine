/*
 * Copyright 2025-2026 Google LLC
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

package dev.ohs.fhir

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.format
import kotlinx.datetime.offsetAt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * KMP-friendly equivalent of `java.time.OffsetDateTime` — a [kotlin.time.Instant] paired with a
 * UTC offset. Mirrors the wire format of the original engine module's `OffsetDateTime` so the
 * KMP engine can surface the same API.
 *
 * Serialized as ISO 8601 with offset, e.g. `"2026-01-15T10:30:00+05:30"` or `"...Z"`.
 */
@Serializable(with = OffsetDateTimeSerializer::class)
data class OffsetDateTime(val instant: Instant, val offset: UtcOffset) {

  override fun toString(): String =
    DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.format {
      setDateTimeOffset(instant, offset)
    }

  companion object {
    /** Returns the current instant paired with the system's current UTC offset. */
    fun now(): OffsetDateTime {
      val instant = Clock.System.now()
      val offset = TimeZone.currentSystemDefault().offsetAt(instant)
      return OffsetDateTime(instant, offset)
    }

    /** Parses ISO 8601 like `"2026-01-15T10:30:00+05:30"` or `"...Z"`. */
    fun parse(text: String): OffsetDateTime {
      val parsed = DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(text)
      return OffsetDateTime(
        instant = parsed.toInstantUsingOffset(),
        offset = parsed.toUtcOffset(),
      )
    }
  }
}

internal object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
  override val descriptor =
    PrimitiveSerialDescriptor("OffsetDateTime", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: OffsetDateTime) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): OffsetDateTime =
    OffsetDateTime.parse(decoder.decodeString())
}
