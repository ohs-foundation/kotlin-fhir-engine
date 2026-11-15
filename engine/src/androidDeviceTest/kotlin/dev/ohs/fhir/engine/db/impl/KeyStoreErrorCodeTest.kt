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

import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.TIMEOUT
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNKNOWN
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNSUPPORTED
import java.security.KeyStoreException
import kotlin.test.Test
import kotlin.test.assertEquals

/** The error code decides whether the key read is retried, so a misread code stops retries. */
class KeyStoreErrorCodeTest {
  @Test
  fun busySecureHardware_isTimeout() {
    assertEquals(TIMEOUT, errorCodeFor("Keystore operation failed -48"))
  }

  @Test
  fun unsupportedAlgorithm_isUnsupported() {
    assertEquals(UNSUPPORTED, errorCodeFor("Keystore operation failed -4"))
  }

  @Test
  fun unknownOrMissingCode_isUnknown() {
    assertEquals(UNKNOWN, errorCodeFor("Keystore operation failed -1000"))
    assertEquals(UNKNOWN, errorCodeFor("Keystore operation failed"))
    assertEquals(UNKNOWN, errorCodeFor(null))
  }

  private fun errorCodeFor(message: String?) =
    KeyStoreException(message).databaseEncryptionException.errorCode
}
