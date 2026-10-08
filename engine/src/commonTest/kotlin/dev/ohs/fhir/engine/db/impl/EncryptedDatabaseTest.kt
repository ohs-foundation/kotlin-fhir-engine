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

import dev.ohs.fhir.engine.DatabaseErrorStrategy
import dev.ohs.fhir.engine.DatabaseErrorStrategy.RECREATE_AT_OPEN
import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * Runs where the platform can encrypt, so desktop and web are not covered here.
 *
 * Reading the file header back is the only evidence in the whole suite that any of this makes bytes
 * unreadable. The cipher version guard and the real Keychain are untested, the Keychain because the
 * simulator has none.
 */
class EncryptedDatabaseTest {
  private val support = encryptionTestSupport

  @BeforeTest
  fun setUp() {
    support?.deleteDatabaseFiles()
    support?.resetDatabaseKey()
  }

  @AfterTest
  fun tearDown() {
    support?.deleteDatabaseFiles()
  }

  @Test
  fun encryptedDatabase_keepsDataAcrossReopen() = runTest {
    val support = support ?: return@runTest
    openDatabase(encrypt = true).use { it.insert(PATIENT) }

    val plainHeader = "SQLite format 3".encodeToByteArray() + 0
    assertFalse(support.readDatabaseHeader(encrypted = true).contentEquals(plainHeader))
    openDatabase(encrypt = true).use {
      assertEquals(PATIENT_ID, it.select(ResourceType.Patient, PATIENT_ID).id)
    }
  }

  @Test
  fun unencryptedDatabase_thenEncrypted_throwsIllegalStateException() = runTest {
    support ?: return@runTest
    openDatabase(encrypt = false).use { it.insert(PATIENT) }

    assertFailsWith<IllegalStateException> { openDatabase(encrypt = true) }
  }

  @Test
  fun encryptedDatabase_thenUnencrypted_throwsIllegalStateException() = runTest {
    support ?: return@runTest
    openDatabase(encrypt = true).use { it.insert(PATIENT) }

    assertFailsWith<IllegalStateException> { openDatabase(encrypt = false) }
  }

  @Test
  fun encryptedDatabase_thenLostKey_throws() = runTest {
    val support = support ?: return@runTest
    openDatabase(encrypt = true).use { it.insert(PATIENT) }
    support.loseDatabaseKey()

    openDatabase(encrypt = true).use { database ->
      assertFailsWith(support.keyMismatchException) {
        database.select(ResourceType.Patient, PATIENT_ID)
      }
    }
  }

  @Test
  fun encryptedDatabase_thenLostKey_recreateAtOpen_startsEmpty() = runTest {
    val support = support ?: return@runTest
    openDatabase(encrypt = true).use { it.insert(PATIENT) }
    support.loseDatabaseKey()

    openDatabase(encrypt = true, errorStrategy = RECREATE_AT_OPEN).use { database ->
      assertFailsWith<ResourceNotFoundException> {
        database.select(ResourceType.Patient, PATIENT_ID)
      }
    }
  }

  private fun openDatabase(
    encrypt: Boolean,
    errorStrategy: DatabaseErrorStrategy = DatabaseErrorStrategy.UNSPECIFIED,
  ) =
    DatabaseImpl(
      testPlatformContext(),
      ResourceIndexer(SearchParamDefinitionsProviderImpl()),
      testStorageDirectory(),
      DatabaseConfig(encrypt = encrypt, errorStrategy = errorStrategy),
    )

  private inline fun <T> Database.use(block: (Database) -> T): T =
    try {
      block(this)
    } finally {
      close()
    }

  private companion object {
    const val PATIENT_ID = "encrypted-patient"
    val PATIENT = Patient(id = PATIENT_ID)
  }
}
