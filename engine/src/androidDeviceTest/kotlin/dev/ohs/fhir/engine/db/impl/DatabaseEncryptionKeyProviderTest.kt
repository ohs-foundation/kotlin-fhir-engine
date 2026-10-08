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

import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class DatabaseEncryptionKeyProviderTest {
  @BeforeTest fun setUp() = deleteTestKeys()

  @AfterTest fun tearDown() = deleteTestKeys()

  @Test
  fun getOrCreatePassphrase_sameAlias_returnsTheSameKeyAfterTheCacheIsCleared() {
    val key = DatabaseEncryptionKeyProvider.getOrCreatePassphrase(ALIAS_NAME)
    DatabaseEncryptionKeyProvider.clearKeyCache()

    assertContentEquals(key, DatabaseEncryptionKeyProvider.getOrCreatePassphrase(ALIAS_NAME))
  }

  @Test
  fun getOrCreatePassphrase_otherAlias_returnsADifferentKey() {
    val key = DatabaseEncryptionKeyProvider.getOrCreatePassphrase(ALIAS_NAME)

    assertFalse(
      key.contentEquals(DatabaseEncryptionKeyProvider.getOrCreatePassphrase(OTHER_ALIAS_NAME)),
    )
  }

  private fun deleteTestKeys() {
    val keyStore = KeyStore.getInstance(DatabaseEncryptionKeyProvider.ANDROID_KEYSTORE_NAME)
    keyStore.load(null)
    keyStore.deleteEntry(ALIAS_NAME)
    keyStore.deleteEntry(OTHER_ALIAS_NAME)
    DatabaseEncryptionKeyProvider.clearKeyCache()
  }

  private companion object {
    const val ALIAS_NAME = "test_key"
    const val OTHER_ALIAS_NAME = "other_test_key"
  }
}
