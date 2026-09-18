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

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * One test per migration on every platform. Each builds the older exported schema, inserts rows,
 * migrates and checks the newer exported schema and the rows.
 */
class ResourceDatabaseMigrationTest {
  private val platformContext = testPlatformContext()
  private val storageDirectory = testStorageDirectory()

  private val tester = MigrationTester(platformContext, storageDirectory)

  @Test
  fun migrate1To2_keepsResources() = runTest {
    tester.createDatabase(1).use { it.insertPatient() }
    tester.runMigrationsAndValidate(2, listOf(ResourceDatabase.MIGRATION_1_2)).use {
      assertEquals(PATIENT_JSON, it.single("SELECT serializedResource FROM ResourceEntity"))
    }
  }

  @Test
  fun migrate2To3_keepsResources() = runTest {
    tester.createDatabase(2).use { it.insertPatient() }
    tester.runMigrationsAndValidate(3, listOf(ResourceDatabase.MIGRATION_2_3)).use {
      assertEquals(PATIENT_JSON, it.single("SELECT serializedResource FROM ResourceEntity"))
    }
  }

  @Test
  fun migrate3To4_keepsResources() = runTest {
    tester.createDatabase(3).use { it.insertPatient() }
    tester.runMigrationsAndValidate(4, listOf(ResourceDatabase.MIGRATION_3_4)).use {
      assertEquals(PATIENT_JSON, it.single("SELECT serializedResource FROM ResourceEntity"))
    }
  }

  @Test
  fun migrate4To5_keepsResources() = runTest {
    tester.createDatabase(4).use { it.insertPatient() }
    tester.runMigrationsAndValidate(5, listOf(ResourceDatabase.MIGRATION_4_5)).use {
      assertEquals(PATIENT_JSON, it.single("SELECT serializedResource FROM ResourceEntity"))
    }
  }

  @Test
  fun migrate5To6_convertsTimestampsToEpochMillis_andCorruptOnesToZero() = runTest {
    val instant = Instant.parse("2024-01-02T03:04:05.678Z")
    tester.createDatabase(5).use {
      it.insertPatient()
      it.executeSQL(
        "INSERT INTO LocalChangeEntity (resourceType, resourceId, timestamp, type, payload) VALUES ('Patient', '$PATIENT_ID', '$instant', 1, '$PATIENT_JSON')",
      )
      it.executeSQL(
        "INSERT INTO LocalChangeEntity (resourceType, resourceId, timestamp, type, payload) VALUES ('Patient', 'corrupt', 'date-not-good', 1, '$PATIENT_JSON')",
      )
    }
    tester.runMigrationsAndValidate(6, listOf(ResourceDatabase.MIGRATION_5_6)).use {
      assertEquals(PATIENT_JSON, it.single("SELECT serializedResource FROM ResourceEntity"))
      assertEquals(
        listOf(instant.toEpochMilliseconds(), 0L),
        it.longs("SELECT timestamp FROM LocalChangeEntity ORDER BY id"),
      )
    }
  }

  @Test
  fun migrate6To7_backfillsLocalChangeResourceUuid() = runTest {
    tester.createDatabase(6).use {
      it.insertPatient()
      it.executeSQL(
        "INSERT INTO LocalChangeEntity (resourceType, resourceId, timestamp, type, payload) VALUES ('Patient', '$PATIENT_ID', 1700000000000, 1, '$PATIENT_JSON')",
      )
    }
    tester.runMigrationsAndValidate(7, listOf(ResourceDatabase.MIGRATION_6_7)).use { connection ->
      connection.prepare("SELECT resourceUuid, resourceId FROM LocalChangeEntity").use {
        it.step()
        assertEquals(PATIENT_UUID, Uuid.fromByteArray(it.getBlob(0)))
        assertEquals(PATIENT_ID, it.getText(1))
      }
    }
  }

  @Test
  fun migrate7To8_extractsReferencesFromPayloads() = runTest {
    val insertPayload =
      """{"resourceType":"Patient","id":"$PATIENT_ID","generalPractitioner":[{"reference":"Practitioner/123"}],"managingOrganization":{"reference":"Organization/123"}}"""
    val updatePayload =
      """[{"op":"replace","path":"/generalPractitioner/0/reference","value":"Practitioner/345"}]"""
    tester.createDatabase(7).use {
      for ((type, payload) in listOf(1 to insertPayload, 2 to updatePayload, 3 to "")) {
        it.executeSQL(
          "INSERT INTO LocalChangeEntity (resourceType, resourceId, resourceUuid, timestamp, type, payload) VALUES ('Patient', '$PATIENT_ID', x'${PATIENT_UUID.toHexString()}', 1700000000000, $type, '$payload')",
        )
      }
    }
    tester.runMigrationsAndValidate(8, listOf(ResourceDatabase.MIGRATION_7_8)).use { connection ->
      val references = mutableListOf<Pair<Long, String>>()
      connection
        .prepare(
          "SELECT localChangeId, resourceReferenceValue FROM LocalChangeResourceReferenceEntity ORDER BY id",
        )
        .use { while (it.step()) references.add(it.getLong(0) to it.getText(1)) }
      assertEquals(
        listOf(1L to "Practitioner/123", 1L to "Organization/123", 2L to "Practitioner/345"),
        references,
      )
    }
  }

  @Test
  fun migrate8To9_keepsTokenIndexRows() = runTest {
    tester.createDatabase(8).use {
      it.insertPatient()
      it.executeSQL(
        "INSERT INTO TokenIndexEntity (resourceUuid, resourceType, index_name, index_path, index_system, index_value) VALUES (x'${PATIENT_UUID.toHexString()}', 'Patient', 'gender', 'Patient.gender', 'http://hl7.org/fhir/administrative-gender', 'female')",
      )
    }
    tester.runMigrationsAndValidate(9, listOf(ResourceDatabase.MIGRATION_8_9)).use {
      // The engine's token search must find the row through the rebuilt index.
      assertEquals(
        PATIENT_JSON,
        it.single(
          """
          SELECT a.serializedResource FROM ResourceEntity a
          WHERE a.resourceType = 'Patient'
            AND a.resourceUuid IN (SELECT resourceUuid FROM TokenIndexEntity
              WHERE resourceType = 'Patient' AND index_name = 'gender' AND index_value = 'female'
                AND IFNULL(index_system, '') = 'http://hl7.org/fhir/administrative-gender')
          """
            .trimIndent(),
        ),
      )
    }
  }

  @Test
  fun migrate9To10_sortsByStringIndex() = runTest {
    val otherUuid = Uuid.parse("541782b3-48f5-4c36-bd20-cae265e974e7")
    tester.createDatabase(9).use {
      it.insertPatient()
      it.insertFamilyNameIndex()
      it.executeSQL(
        "INSERT INTO ResourceEntity (resourceUuid, resourceType, resourceId, serializedResource) VALUES (x'${otherUuid.toHexString()}', 'Patient', 'other', '{\"resourceType\":\"Patient\",\"id\":\"other\"}')",
      )
      it.executeSQL(
        "INSERT INTO StringIndexEntity (resourceUuid, resourceType, index_name, index_path, index_value) VALUES (x'${otherUuid.toHexString()}', 'Patient', 'family', 'Patient.name.family', 'Adams')",
      )
    }
    tester.runMigrationsAndValidate(10, listOf(ResourceDatabase.MIGRATION_9_10)).use { connection ->
      // The engine's sort must order by the rebuilt index.
      val ids =
        connection
          .prepare(
            """
            SELECT a.resourceId
            FROM ResourceEntity a
            LEFT JOIN StringIndexEntity b
            ON a.resourceUuid = b.resourceUuid AND b.index_name = 'family'
            WHERE a.resourceType = 'Patient'
            GROUP BY a.resourceUuid
            HAVING MAX(IFNULL(b.index_value,0)) >= -9223372036854775808
            ORDER BY IFNULL(b.index_value, -9223372036854775808) ASC
            """
              .trimIndent(),
          )
          .use { buildList { while (it.step()) add(it.getText(0)) } }
      assertEquals(listOf("other", PATIENT_ID), ids)
    }
  }

  @Test
  fun migrate10To11_keepsRows() = runTest {
    tester.createDatabase(10).use {
      it.insertPatient()
      it.insertFamilyNameIndex()
      it.executeSQL(
        "INSERT INTO LocalChangeEntity (resourceType, resourceId, resourceUuid, timestamp, type, payload) VALUES ('Patient', '$PATIENT_ID', x'${PATIENT_UUID.toHexString()}', 1700000000000, 1, '$PATIENT_JSON')",
      )
    }
    tester.runMigrationsAndValidate(11, listOf(ResourceDatabase.MIGRATION_10_11)).use {
      assertEquals(PATIENT_JSON, it.single("SELECT serializedResource FROM ResourceEntity"))
      assertEquals("Jones", it.single("SELECT index_value FROM StringIndexEntity"))
      assertEquals(PATIENT_JSON, it.single("SELECT payload FROM LocalChangeEntity"))
    }
  }

  @Test
  fun alphaDatabase_failsWithAClearMessage() = runTest {
    // KMP engine 2.0.0-alpha01 to alpha03 stored resourceUuid as TEXT at version 2.
    val alphaSchema =
      ExportedSchemas.ddl(11).map { it.replace("`resourceUuid` BLOB", "`resourceUuid` TEXT") }
    tester.createDatabase(2, alphaSchema).close()
    val database =
      DatabaseImpl(
        platformContext,
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        storageDirectory,
      )
    try {
      val failure = assertFailsWith<IllegalStateException> { database.getLocalChangesCount() }
      assertTrue(failure.message!!.contains("2.0.0-alpha01, alpha02 or alpha03"))
    } finally {
      database.close()
      // Leave a database later tests can open, since some platforms share one file.
      tester.createDatabase(ResourceDatabase.VERSION).close()
    }
  }

  @Test
  fun migrationsCoverEveryVersion() {
    assertEquals(
      (1 until ResourceDatabase.VERSION).map { it to it + 1 },
      ResourceDatabase.MIGRATIONS.map { it.startVersion to it.endVersion },
    )
  }

  private suspend fun SQLiteConnection.insertPatient() =
    executeSQL(
      "INSERT INTO ResourceEntity (resourceUuid, resourceType, resourceId, serializedResource) VALUES (x'${PATIENT_UUID.toHexString()}', 'Patient', '$PATIENT_ID', '$PATIENT_JSON')",
    )

  private suspend fun SQLiteConnection.insertFamilyNameIndex() =
    executeSQL(
      "INSERT INTO StringIndexEntity (resourceUuid, resourceType, index_name, index_path, index_value) VALUES (x'${PATIENT_UUID.toHexString()}', 'Patient', 'family', 'Patient.name.family', 'Jones')",
    )

  private suspend fun SQLiteConnection.single(sql: String): String =
    prepare(sql).use {
      assertTrue(it.step(), "no row for: $sql")
      it.getText(0)
    }

  private suspend fun SQLiteConnection.longs(sql: String): List<Long> =
    prepare(sql).use { statement ->
      buildList { while (statement.step()) add(statement.getLong(0)) }
    }

  private companion object {
    const val PATIENT_ID = "android-fhir-patient"
    val PATIENT_UUID = Uuid.parse("5f4c3a2b-1e0d-4c9b-8a7f-6e5d4c3b2a19")
    const val PATIENT_JSON =
      """{"resourceType":"Patient","id":"$PATIENT_ID","name":[{"family":"Jones"}]}"""
  }
}
