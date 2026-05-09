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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.serialization.json.Json

class OffsetDateTimeTest {

  @Test
  fun parse_isoWithPositiveOffset_roundTripsToString() {
    val text = "2026-01-15T10:30:00+05:30"
    assertEquals(text, OffsetDateTime.parse(text).toString())
  }

  @Test
  fun parse_isoWithNegativeOffset_roundTripsToString() {
    val text = "2026-01-15T10:30:00-08:00"
    assertEquals(text, OffsetDateTime.parse(text).toString())
  }

  @Test
  fun parse_isoWithZ_roundTripsAsZ() {
    val text = "2026-01-15T05:00:00Z"
    assertEquals(text, OffsetDateTime.parse(text).toString())
  }

  @Test
  fun parse_isoWithZ_offsetIsZero() {
    val parsed = OffsetDateTime.parse("2026-01-15T05:00:00Z")
    assertEquals(UtcOffset.ZERO, parsed.offset)
  }

  @Test
  fun parse_isoWithFractionalSeconds_preservesNanos() {
    val parsed = OffsetDateTime.parse("2026-01-15T10:30:00.123456789+00:00")
    assertEquals(Instant.parse("2026-01-15T10:30:00.123456789Z"), parsed.instant)
  }

  @Test
  fun now_returnsNonNull() {
    assertNotNull(OffsetDateTime.now())
  }

  @Test
  fun now_offsetMatchesSystemTimeZone() {
    val now = OffsetDateTime.now()
    val expectedOffset = TimeZone.currentSystemDefault().offsetAt(now.instant)
    assertEquals(expectedOffset, now.offset)
  }

  @Test
  fun equals_sameInstantAndOffset_true() {
    val instant = Instant.parse("2026-01-15T10:00:00Z")
    val offset = UtcOffset(hours = 5, minutes = 30)
    val a = OffsetDateTime(instant, offset)
    val b = OffsetDateTime(instant, offset)
    assertEquals(a, b)
  }

  @Test
  fun equals_sameInstantDifferentOffset_false() {
    val instant = Instant.parse("2026-01-15T10:00:00Z")
    val a = OffsetDateTime(instant, UtcOffset(hours = 5))
    val b = OffsetDateTime(instant, UtcOffset(hours = -8))
    assertNotEquals(a, b)
  }

  @Test
  fun parse_invalidString_throws() {
    assertFails { OffsetDateTime.parse("not a date") }
  }

  @Test
  fun serializer_jsonRoundTrip() {
    val value = OffsetDateTime.parse("2026-01-15T10:30:00+05:30")
    val encoded = Json.encodeToString(OffsetDateTime.serializer(), value)
    val decoded = Json.decodeFromString(OffsetDateTime.serializer(), encoded)
    assertEquals(value, decoded)
  }

  @Test
  fun serializer_encodesAsJsonString() {
    val value = OffsetDateTime.parse("2026-01-15T10:30:00+05:30")
    val encoded = Json.encodeToString(OffsetDateTime.serializer(), value)
    // Encoded form is a JSON string literal, e.g. "\"2026-01-15T10:30:00+05:30\""
    assertTrue(encoded.startsWith("\""), "expected JSON string, got: $encoded")
    assertTrue(encoded.endsWith("\""), "expected JSON string, got: $encoded")
    assertEquals("\"2026-01-15T10:30:00+05:30\"", encoded)
  }
}
