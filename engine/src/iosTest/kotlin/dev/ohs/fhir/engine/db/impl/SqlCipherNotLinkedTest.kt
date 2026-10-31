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
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNSUPPORTED
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager

/** Only runs in iosSimulatorArm64SystemSqliteTest, the test binary without SQLCipher. */
@OptIn(ExperimentalForeignApi::class)
class SqlCipherNotLinkedTest {
  @Test
  fun encryptedDatabase_withoutSqlCipher_throwsUnsupported() = runTest {
    if (isSqlCipherLinked) return@runTest
    // Other tests leave a plain database behind, which blocks opening an encrypted one.
    NSFileManager.defaultManager.removeItemAtPath(
      databaseFileName(Unit, null, DatabaseConfig()),
      error = null,
    )
    val config = DatabaseConfig(encrypt = true)
    val database =
      DatabaseImpl(
        testPlatformContext(),
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        testStorageDirectory(),
        config,
      )
    try {
      val exception =
        assertFailsWith<DatabaseEncryptionException> {
          database.select(ResourceType.Patient, "any")
        }
      assertEquals(UNSUPPORTED, exception.errorCode)
      // An empty file left here would stop the app from turning encryption off.
      assertFalse(databaseFileExists(Unit, null, config))
    } finally {
      database.close()
      NSFileManager.defaultManager.removeItemAtPath(
        databaseFileName(Unit, null, config),
        error = null,
      )
    }
  }
}
