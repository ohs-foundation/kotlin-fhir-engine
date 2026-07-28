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
package dev.ohs.fhir.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FhirEngineConfigurationTest {

  @Test
  fun enableEncryptionIfSupported_throws_because_encryption_is_not_yet_supported() {
    assertFailsWith<IllegalArgumentException> {
      FhirEngineConfiguration(enableEncryptionIfSupported = true)
    }
  }

  @Test
  fun default_configuration_does_not_throw() {
    FhirEngineConfiguration()
  }

  @Test
  fun encryption_request_can_fall_back_to_an_unencrypted_configuration() {
    val configuration =
      try {
        FhirEngineConfiguration(enableEncryptionIfSupported = true)
      } catch (_: IllegalArgumentException) {
        FhirEngineConfiguration()
      }
    assertFalse(configuration.enableEncryptionIfSupported)
  }
}
