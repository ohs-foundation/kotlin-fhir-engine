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

import dev.ohs.fhir.engine.ResourceStorageFormat
import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.search.SearchQuery
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Every storage format has to come back out of the database as the resource that went in, and has
 * to leave the local change log in FHIR JSON whatever it stored. Runs on every platform because
 * each one has its own SQLite driver and its own kotlinx-serialization target.
 */
class ResourceStorageFormatTest {

  @Test
  fun everyFormat_roundTripsThroughTheDatabase() = runTest {
    forEachFormat { database ->
      database.insert(PATIENT)

      assertEquals(PATIENT, database.select(ResourceType.Patient, PATIENT_ID))
      assertEquals(
        listOf(PATIENT),
        database.search<Patient>(SearchQuery(SELECT_ALL, emptyList())).map { it.resource },
      )
    }
  }

  @Test
  fun everyFormat_recordsLocalChangesAsJson() = runTest {
    forEachFormat { database ->
      database.insert(PATIENT)
      database.update(PATIENT.copy(gender = Enumeration(value = AdministrativeGender.Female)))

      val payloads = database.getLocalChanges(ResourceType.Patient, PATIENT_ID).map { it.payload }
      assertEquals(2, payloads.size)
      assertEquals(fhirJsonParser.encodeToString(PATIENT), payloads.first())
      assertTrue(payloads.last().contains("\"female\""), payloads.last())
    }
  }

  @Test
  fun aFormatSwitch_leavesEarlierRowsReadable() = runTest {
    val storageDirectory = testStorageDirectory()
    database(ResourceStorageFormat.JSON, storageDirectory).use { it.insert(PATIENT) }
    database(ResourceStorageFormat.PROTOBUF, storageDirectory).use { database ->
      assertEquals(PATIENT, database.select(ResourceType.Patient, PATIENT_ID))

      database.insert(PATIENT.copy(id = OTHER_ID))
      assertEquals(OTHER_ID, database.select(ResourceType.Patient, OTHER_ID).id)
      assertEquals(PATIENT, database.select(ResourceType.Patient, PATIENT_ID))
    }
  }

  private suspend fun forEachFormat(block: suspend (Database) -> Unit) {
    ResourceStorageFormat.entries.forEach { format ->
      database(format, testStorageDirectory()).use { database ->
        database.clearDatabase()
        block(database)
      }
    }
  }

  private fun database(format: ResourceStorageFormat, storageDirectory: String?): Database =
    DatabaseImpl(
      testPlatformContext(),
      ResourceIndexer(SearchParamDefinitionsProviderImpl()),
      storageDirectory = storageDirectory,
      resourceStorageFormat = format,
    )

  private suspend fun Database.use(block: suspend (Database) -> Unit) {
    try {
      block(this)
    } finally {
      close()
    }
  }

  private companion object {
    const val PATIENT_ID = "storage-format-patient"
    const val OTHER_ID = "storage-format-patient-2"
    const val SELECT_ALL = "SELECT resourceUuid, serializedResource FROM ResourceEntity"

    val PATIENT =
      Patient(
        id = PATIENT_ID,
        gender = Enumeration(value = AdministrativeGender.Male),
        name = listOf(HumanName(family = FhirString(value = "Jones"))),
      )
  }
}
