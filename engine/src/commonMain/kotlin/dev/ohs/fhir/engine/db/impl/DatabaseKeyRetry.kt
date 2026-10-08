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

/**
 * Reads the database key with [read], trying again while the platform key store reports a timeout.
 *
 * Android's keymaster reports its secure hardware busy. The iOS Keychain refuses everything until
 * the first unlock after a reboot, which is when a background sync may run. Any other failure, and
 * the last attempt, go straight to the caller.
 *
 * [sleep] takes milliseconds and blocks. The drivers call this from inside open, which Room runs
 * off the main thread.
 */
internal fun <T> readKeyRetryingTimeouts(sleep: (Long) -> Unit, read: () -> T): T {
  repeat(MAX_ATTEMPTS - 1) { attempt ->
    try {
      return read()
    } catch (exception: DatabaseEncryptionException) {
      if (exception.errorCode != TIMEOUT) throw exception
      sleep(RETRY_DELAY_MILLIS * (attempt + 1))
    }
  }
  return read()
}

private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MILLIS = 1000L
