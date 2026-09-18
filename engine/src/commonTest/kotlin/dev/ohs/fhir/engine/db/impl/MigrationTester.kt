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

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.open
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlin.test.assertEquals

/**
 * Builds the database at an exported schema, runs migrations and compares the result with the
 * exported target schema. Room's own helper does not exist for web and reads files on iOS.
 */
internal class MigrationTester(platformContext: Any, storageDirectory: String?) {
  private val driver = databaseDriver()
  private val fileName = databaseFileName(platformContext, storageDirectory)

  init {
    createDatabaseDirectory(platformContext, storageDirectory)
  }

  /** [statements] default to the exported schema of [version]. */
  suspend fun createDatabase(
    version: Int,
    statements: List<String> = ExportedSchemas.ddl(version),
  ): SQLiteConnection {
    val connection = driver.open(fileName)
    connection.dropAllTables()
    statements.forEach { connection.executeSQL(it) }
    connection.executeSQL("PRAGMA user_version = $version")
    return connection
  }

  suspend fun openConnection(): SQLiteConnection = driver.open(fileName)

  /** Asserts the migrated schema equals a fresh one built from the exported schema [version]. */
  suspend fun runMigrationsAndValidate(
    version: Int,
    migrations: List<Migration>,
  ): SQLiteConnection {
    val connection = driver.open(fileName)
    connection.executeSQL("PRAGMA foreign_keys = OFF")
    migrations.forEach { it.migrate(connection) }
    connection.executeSQL("PRAGMA user_version = $version")

    val expected =
      driver.open(":memory:").use { scratch ->
        ExportedSchemas.ddl(version).forEach { scratch.executeSQL(it) }
        scratch.schema()
      }
    assertEquals(expected, connection.schema(), "schema after migrating to $version")
    return connection
  }

  private suspend fun SQLiteConnection.dropAllTables() {
    executeSQL("PRAGMA foreign_keys = OFF")
    tables().forEach { executeSQL("DROP TABLE IF EXISTS `$it`") }
  }

  private suspend fun SQLiteConnection.tables(): List<String> =
    rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") {
        it.getText(0)
      }
      .filter { it != "room_master_table" && it != "android_metadata" }

  /** The parts Room validates, as comparable strings per table. */
  private suspend fun SQLiteConnection.schema(): Map<String, List<String>> =
    tables().sorted().associateWith { table ->
      (columns(table) + foreignKeys(table) + indices(table)).sorted()
    }

  // table_info columns are cid, name, type, notnull, dflt_value, pk
  private suspend fun SQLiteConnection.columns(table: String): List<String> =
    rows("PRAGMA table_info(`$table`)") {
      val name = it.getText(1)
      val type = it.getText(2)
      val notNull = it.getLong(3)
      val primaryKey = it.getLong(5)
      // ALTER TABLE ADD COLUMN records DEFAULT NULL where the exported CREATE TABLE has no
      // default. Room ignores that difference, so this does too.
      val default = it.textOrNull(4)?.takeIf { d -> d != "NULL" }
      "column $name $type notnull=$notNull pk=$primaryKey" +
        (default?.let { d -> " default=$d" } ?: "")
    }

  // foreign_key_list columns are id, seq, table, from, to, on_update, on_delete, match
  private suspend fun SQLiteConnection.foreignKeys(table: String): List<String> =
    rows("PRAGMA foreign_key_list(`$table`)") {
      "foreignKey ${it.getText(3)} -> ${it.getText(2)}.${it.getText(4)} onUpdate=${it.getText(5)} onDelete=${it.getText(6)}"
    }

  // index_list columns are seq, name, unique, origin, partial. Origin c means CREATE INDEX. Other
  // origins back primary keys and UNIQUE constraints.
  private suspend fun SQLiteConnection.indices(table: String): List<String> =
    rows("PRAGMA index_list(`$table`)") {
        Index(name = it.getText(1), unique = it.getLong(2) == 1L, explicit = it.getText(3) == "c")
      }
      .filter { it.explicit }
      .map { "index ${it.name} unique=${it.unique} on ${indexColumns(it.name).joinToString(",")}" }

  // index_xinfo columns are seqno, cid, name, desc, coll, key. The last row is the rowid with cid
  // -1.
  private suspend fun SQLiteConnection.indexColumns(index: String): List<String> =
    rows("PRAGMA index_xinfo(`$index`)") {
        val isRowId = it.getLong(1) < 0
        if (isRowId) null else it.getText(2) + if (it.getLong(3) == 1L) " desc" else ""
      }
      .filterNotNull()

  private data class Index(val name: String, val unique: Boolean, val explicit: Boolean)

  private suspend fun <T> SQLiteConnection.rows(
    sql: String,
    read: (SQLiteStatement) -> T,
  ): List<T> =
    prepare(sql).use { statement -> buildList { while (statement.step()) add(read(statement)) } }

  private fun SQLiteStatement.textOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)
}
