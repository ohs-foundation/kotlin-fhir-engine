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

import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding

@OptIn(ExperimentalForeignApi::class)
class UnreadableDatabaseTest {
  @Test
  fun notADatabase_isUnreadable() {
    assertTrue(
      SQLiteException("Error code: 26, message: file is not a database").isUnreadableDatabase,
    )
  }

  @Test
  fun corrupt_isUnreadable() {
    assertTrue(
      SQLiteException("Error code: 11, message: database disk image is malformed")
        .isUnreadableDatabase,
    )
    // SQLITE_CORRUPT_INDEX, an extended code of SQLITE_CORRUPT.
    assertTrue(SQLiteException("Error code: 779").isUnreadableDatabase)
  }

  @Test
  fun lockedOrFull_isNotUnreadable() {
    assertFalse(SQLiteException("Error code: 5, message: database is locked").isUnreadableDatabase)
    assertFalse(
      SQLiteException("Error code: 13, message: database or disk is full").isUnreadableDatabase,
    )
    assertFalse(SQLiteException("Error code: 10, message: disk I/O error").isUnreadableDatabase)
  }

  @Test
  fun noCode_isNotUnreadable() {
    assertFalse(SQLiteException("file is not a database").isUnreadableDatabase)
  }

  // The cases above assume the driver's message format. This checks it with a real driver error.
  @Test
  fun driverError_onFileThatIsNotADatabase_isUnreadable() {
    val fileName = NSTemporaryDirectory() + "not-a-database.db"

    @Suppress("CAST_NEVER_SUCCEEDS") val junk = "x".repeat(4096) as NSString
    NSFileManager.defaultManager.createFileAtPath(
      fileName,
      contents = junk.dataUsingEncoding(NSUTF8StringEncoding),
      attributes = null,
    )
    try {
      val connection = NativeSQLiteDriver().open(fileName)
      try {
        val exception =
          assertFailsWith<SQLiteException> {
            connection.prepare("SELECT count(*) FROM sqlite_master").use { it.step() }
          }
        assertTrue(exception.isUnreadableDatabase, exception.message)
      } finally {
        connection.close()
      }
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(fileName, error = null)
    }
  }
}
