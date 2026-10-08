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

import dev.ohs.fhir.engine.db.DatabaseEncryptionException
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.TIMEOUT
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNSUPPORTED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * The policy both encrypting platforms share. It went untested long enough for iOS to classify a
 * locked Keychain as a timeout and never retry it.
 */
class DatabaseKeyRetryTest {
  @Test
  fun timeout_isRetried_withAGrowingPause() {
    val waits = mutableListOf<Long>()
    var attempts = 0

    val key =
      readKeyRetryingTimeouts(sleep = { waits += it }) {
        attempts++
        if (attempts < 3) throw DatabaseEncryptionException(null, TIMEOUT)
        "key"
      }

    assertEquals("key", key)
    assertEquals(3, attempts)
    assertEquals(listOf(1000L, 2000L), waits)
  }

  @Test
  fun anyOtherFailure_isNotRetried() {
    var attempts = 0

    assertFailsWith<DatabaseEncryptionException> {
      readKeyRetryingTimeouts(sleep = { fail("an unsupported key store must not be waited on") }) {
        attempts++
        throw DatabaseEncryptionException(null, UNSUPPORTED)
      }
    }

    assertEquals(1, attempts)
  }

  @Test
  fun aTimeoutThatNeverClears_throwsFromTheLastAttempt() {
    var attempts = 0

    val failure =
      assertFailsWith<DatabaseEncryptionException> {
        readKeyRetryingTimeouts(sleep = {}) {
          attempts++
          throw DatabaseEncryptionException(null, TIMEOUT, "attempt $attempts")
        }
      }

    assertEquals(3, attempts)
    assertEquals("attempt 3", failure.message)
  }
}
