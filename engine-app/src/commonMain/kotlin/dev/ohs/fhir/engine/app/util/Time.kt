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
package dev.ohs.fhir.engine.app.util

import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Returns the current wall-clock time in the system time zone. */
fun now(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/** Formats a [LocalDateTime] as `yyyy-MM-dd HH:mm:ss`. */
fun LocalDateTime.formatTimestamp(): String {
  // ISO-8601, e.g. "2026-06-13T14:30:45(.123)"; seconds are omitted when zero.
  val noFraction = toString().substringBefore('.')
  val withSeconds = if (noFraction.length == 16) "$noFraction:00" else noFraction
  return withSeconds.replace('T', ' ')
}
