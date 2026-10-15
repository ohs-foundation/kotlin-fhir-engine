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

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import co.touchlab.kermit.Logger
import kotlin.uuid.Uuid

/**
 * Converts a database from releases 2.0.0-alpha01 to alpha04 to schema version 11.
 *
 * Those releases stamped the file as version 2 with the version 11 layout, except that resourceUuid
 * was a TEXT column. Every table with that column is rebuilt from its frozen version 11 definition
 * and its rows copied across with each uuid turned into its 16 bytes. The caller runs the step
 * migrations from [Schema11.VERSION] on, so any later version is reached the way every other
 * database reaches it.
 *
 * This has to stay for good, an alpha install can be upgraded at any date.
 */
internal object AlphaDatabaseConverter {
  private const val UUID_COLUMN = "resourceUuid"

  /**
   * True for a version 2 file with the alpha layout. The android-fhir engine's version 2 has no
   * lastUpdatedLocal column, it only gains one at version 5, and its uuids were always BLOBs.
   */
  suspend fun isAlphaDatabase(c: SQLiteConnection): Boolean {
    val columns = c.columnTypes("ResourceEntity")
    return columns["lastUpdatedLocal"] != null && columns[UUID_COLUMN] == "TEXT"
  }

  suspend fun convert(c: SQLiteConnection) {
    // Room runs migrations with foreign keys on. Deferring the checks lets child rows be copied
    // before their parents, and legacy renames stop the rename below from redirecting the
    // children's foreign keys to the old table. Both are restored, although the deferral would
    // reset itself when Room commits.
    c.executeSQL("PRAGMA defer_foreign_keys = ON")
    c.executeSQL("PRAGMA legacy_alter_table = ON")
    try {
      val tables = Schema11.tables.filter { it.hasColumn(UUID_COLUMN) }
      tables.forEach { c.rebuild(it) }
      Logger.i { "Converted the database from an alpha release, ${tables.size} tables rebuilt." }
    } finally {
      c.executeSQL("PRAGMA legacy_alter_table = OFF")
      c.executeSQL("PRAGMA defer_foreign_keys = OFF")
    }
  }

  // The columns come from the file, not from Schema11.Table.columns, so every column the old
  // table really has is copied.
  private suspend fun SQLiteConnection.rebuild(table: Schema11.Table) {
    val old = "_old_${table.name}"
    executeSQL("ALTER TABLE `${table.name}` RENAME TO `$old`")
    executeSQL(table.createSql)
    val columns = columnTypes(old).keys.toList()
    val columnList = columns.joinToString { "`$it`" }
    val placeholders = columns.joinToString { "?" }
    prepare("SELECT $columnList FROM `$old`").use { source ->
      // One insert statement for the whole table, reset between rows.
      prepare("INSERT INTO `${table.name}` ($columnList) VALUES ($placeholders)").use { target ->
        while (source.step()) {
          columns.forEachIndexed { i, column -> target.copy(source, i, table.name, column) }
          target.step()
          target.reset()
        }
      }
    }
    executeSQL("DROP TABLE `$old`")
    table.indexSql.forEach { executeSQL(it) }
  }

  private fun SQLiteStatement.copy(source: SQLiteStatement, i: Int, table: String, column: String) {
    when (source.getColumnType(i)) {
      SQLITE_DATA_NULL -> bindNull(i + 1)
      SQLITE_DATA_INTEGER -> bindLong(i + 1, source.getLong(i))
      SQLITE_DATA_FLOAT -> bindDouble(i + 1, source.getDouble(i))
      SQLITE_DATA_BLOB -> bindBlob(i + 1, source.getBlob(i))
      SQLITE_DATA_TEXT ->
        if (column == UUID_COLUMN) {
          bindBlob(i + 1, uuidBytes(source.getText(i), table, column))
        } else {
          bindText(i + 1, source.getText(i))
        }
      else -> error("Unexpected storage class for $table.$column")
    }
  }

  // A failure aborts the migration and leaves the database unopenable, so the message has to say
  // where the bad value is.
  private fun uuidBytes(text: String, table: String, column: String) =
    try {
      Uuid.parse(text).toByteArray()
    } catch (e: IllegalArgumentException) {
      throw IllegalStateException("$table.$column holds \"$text\", which is not a uuid", e)
    }

  // Keyed by the exact name Room declares. SQLite itself compares column names case insensitively.
  private suspend fun SQLiteConnection.columnTypes(table: String): Map<String, String> =
    prepare("PRAGMA table_info(`$table`)").use { statement ->
      buildMap { while (statement.step()) put(statement.getText(1), statement.getText(2)) }
    }
}
