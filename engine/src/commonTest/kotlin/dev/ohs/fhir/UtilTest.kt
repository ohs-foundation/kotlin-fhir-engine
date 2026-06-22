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
package dev.ohs.fhir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// TODO: `operationOutcomeIsSuccess_*` tests skipped pending sync implementation.
class UtilTest {

  @Test
  fun isValidDateOnly_shouldReturnTrue_forValidDateOnlyString() {
    assertTrue(isValidDateOnly("2022-01-02"))
  }

  @Test
  fun isValidDateOnly_shouldReturnFalse_forValidDatetimeString() {
    assertFalse(isValidDateOnly("2022-01-02 00:00:01"))
  }

  @Test
  fun isValidDateOnly_shouldReturnFalse_forInvalidDateString() {
    assertFalse(isValidDateOnly("33-33-33"))
  }

  @Test
  fun percentOf_shouldReturnZero_whenTotalIsZero() {
    assertEquals(0.0, percentOf(0, 0))
  }

  @Test
  fun percentOf_shouldReturnPercentage() {
    assertEquals(0.5, percentOf(25, 50))
  }
}
