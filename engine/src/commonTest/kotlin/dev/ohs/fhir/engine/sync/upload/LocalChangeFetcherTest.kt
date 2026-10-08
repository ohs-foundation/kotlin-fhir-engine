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
package dev.ohs.fhir.engine.sync.upload

import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.db.impl.DatabaseConfig
import dev.ohs.fhir.engine.db.impl.DatabaseImpl
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalChangeFetcherTest {
  private lateinit var database: Database

  @BeforeTest
  fun setUp() {
    database =
      DatabaseImpl(
        testPlatformContext(),
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        testStorageDirectory(),
        DatabaseConfig(inMemory = true),
      )
  }

  @AfterTest
  fun tearDown() {
    database.close()
  }

  @Test
  fun allChanges_next_returnsAllLocalChanges() = runTest {
    val fetcher = allChangesFetcher()

    val localChanges = fetcher.next()

    assertEquals(2, fetcher.next().size)
    assertEquals(
      setOf(TEST_PATIENT_1_ID, TEST_PATIENT_2_ID),
      localChanges.map { it.resourceId }.toSet(),
    )
  }

  @Test
  fun allChanges_hasNext_isTrueWhileLocalChangesRemain() = runTest {
    val fetcher = allChangesFetcher()

    assertTrue(fetcher.hasNext())
    database.deleteUpdates(listOf(TEST_PATIENT_1, TEST_PATIENT_2))
    assertFalse(fetcher.hasNext())
  }

  @Test
  fun allChanges_getProgress_countsRemainingLocalChanges() = runTest {
    val fetcher = allChangesFetcher()

    assertEquals(SyncUploadProgress(2, 2), fetcher.getProgress())
    database.deleteUpdates(listOf(TEST_PATIENT_1))
    assertEquals(SyncUploadProgress(1, 2), fetcher.getProgress())
    database.deleteUpdates(listOf(TEST_PATIENT_2))
    assertEquals(SyncUploadProgress(0, 2), fetcher.getProgress())
  }

  @Test
  fun perResource_initTotalCount_countsEveryLocalChange() = runTest {
    val fetcher = perResourceFetcher()

    assertEquals(3, fetcher.getProgress().initialTotal)
  }

  @Test
  fun perResource_hasNext_isTrueWhileAnyResourceHasLocalChanges() = runTest {
    val fetcher = perResourceFetcher()

    assertTrue(fetcher.hasNext())
    database.deleteUpdates(listOf(TEST_PATIENT_1))
    assertTrue(fetcher.hasNext())
    database.deleteUpdates(listOf(TEST_PATIENT_2))
    assertFalse(fetcher.hasNext())
  }

  @Test
  fun perResource_next_returnsTheChangesOfTheEarliestChangedResource() = runTest {
    val fetcher = perResourceFetcher()

    val firstSetOfChanges = fetcher.next()
    database.deleteUpdates(listOf(TEST_PATIENT_1))
    val secondSetOfChanges = fetcher.next()

    assertEquals(
      listOf(LocalChange.Type.INSERT, LocalChange.Type.UPDATE),
      firstSetOfChanges.map { it.type },
    )
    assertTrue(firstSetOfChanges.all { it.resourceId == TEST_PATIENT_1_ID })
    assertEquals(LocalChange.Type.INSERT, secondSetOfChanges.single().type)
    assertEquals(TEST_PATIENT_2_ID, secondSetOfChanges.single().resourceId)
  }

  private suspend fun allChangesFetcher(): AllChangesLocalChangeFetcher {
    database.insert(TEST_PATIENT_1, TEST_PATIENT_2)
    return AllChangesLocalChangeFetcher(database).apply { initTotalCount() }
  }

  private suspend fun perResourceFetcher(): PerResourceLocalChangeFetcher {
    database.insert(TEST_PATIENT_1, TEST_PATIENT_2)
    database.update(TEST_PATIENT_1.copy(gender = Enumeration(value = AdministrativeGender.Female)))
    return PerResourceLocalChangeFetcher(database).apply { initTotalCount() }
  }

  private companion object {
    const val TEST_PATIENT_1_ID = "test_patient_1"
    val TEST_PATIENT_1 =
      Patient(id = TEST_PATIENT_1_ID, gender = Enumeration(value = AdministrativeGender.Male))
    const val TEST_PATIENT_2_ID = "test_patient_2"
    val TEST_PATIENT_2 =
      Patient(id = TEST_PATIENT_2_ID, gender = Enumeration(value = AdministrativeGender.Male))
  }
}
