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

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.execSQL
import dev.ohs.fhir.engine.DatabaseErrorStrategy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

internal actual fun getDatabaseBuilder(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): RoomDatabase.Builder<ResourceDatabase> {
  val builder =
    if (config.inMemory) {
      Room.inMemoryDatabaseBuilder<ResourceDatabase>()
    } else {
      createDatabaseDirectory(platformContext, storageDirectory)
      Room.databaseBuilder<ResourceDatabase>(
        databaseFileName(platformContext, storageDirectory, config.encrypt),
      )
    }
  return builder.setDriver(databaseDriver(config)).setQueryCoroutineContext(Dispatchers.IO)
}

internal actual val isDatabaseEncryptionSupported: Boolean = true

internal actual fun databaseDriver(config: DatabaseConfig): SQLiteDriver =
  if (config.encrypt) EncryptedDatabaseDriver(config.errorStrategy) else NativeSQLiteDriver()

/**
 * Keys every connection with the Keychain key. The engine does not ship SQLCipher, the app links it
 * in place of the system SQLite, so the first open checks that it actually did.
 */
private class EncryptedDatabaseDriver(private val errorStrategy: DatabaseErrorStrategy) :
  SQLiteDriver {
  private val driver = NativeSQLiteDriver()

  // Read on the first open, like Android, so a Keychain failure surfaces at first database access.
  // Lazy keeps it to one read for Room's concurrent opens.
  private val key by lazy {
    readKeyRetryingTimeouts({ NSThread.sleepForTimeInterval(it / 1000.0) }) {
      DatabaseEncryptionKeyProvider.getOrCreateKey()
    }
  }

  override fun open(fileName: String): SQLiteConnection =
    try {
      openKeyed(fileName)
    } catch (exception: SQLiteException) {
      // A database the current key cannot read is unrecoverable, so the caller may ask for a new
      // one.
      if (errorStrategy != DatabaseErrorStrategy.RECREATE_AT_OPEN) throw exception
      deleteDatabaseFiles(fileName)
      openKeyed(fileName)
    }

  private fun openKeyed(fileName: String): SQLiteConnection {
    val connection = driver.open(fileName)
    try {
      connection.execSQL("PRAGMA key = \"x'${key.toHexString()}'\"")
      check(
        connection.prepare("PRAGMA cipher_version").use { it.step() && it.getText(0).isNotEmpty() },
      ) {
        "SQLCipher is not linked into the app, so the database cannot be encrypted."
      }
      // Reads the header, which fails when the key does not match the file.
      connection.prepare("SELECT count(*) FROM sqlite_master").use { it.step() }
    } catch (exception: Throwable) {
      connection.close()
      throw exception
    }
    excludeFromBackup(fileName)
    return connection
  }

  // Excluded because a restored copy could not be read without the key. The write ahead log and its
  // shared memory file hold content too, best effort for those, they appear after the first write.
  @OptIn(ExperimentalForeignApi::class)
  private fun excludeFromBackup(fileName: String) {
    for (suffix in DATABASE_FILE_SUFFIXES) {
      NSURL.fileURLWithPath(fileName + suffix)
        .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun deleteDatabaseFiles(fileName: String) {
    for (suffix in DATABASE_FILE_SUFFIXES) {
      NSFileManager.defaultManager.removeItemAtPath(fileName + suffix, error = null)
    }
  }

  private companion object {
    val DATABASE_FILE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
  }
}

internal actual fun databaseFileName(
  platformContext: Any,
  storageDirectory: String?,
  encrypted: Boolean,
): String =
  "${applicationSupportDirectory()}/${if (encrypted) ENCRYPTED_DATABASE_NAME else DATABASE_NAME}"

internal actual fun databaseFileExists(
  platformContext: Any,
  storageDirectory: String?,
  encrypted: Boolean,
): Boolean =
  NSFileManager.defaultManager.fileExistsAtPath(
    databaseFileName(platformContext, storageDirectory, encrypted),
  )

@OptIn(ExperimentalForeignApi::class)
internal actual fun createDatabaseDirectory(platformContext: Any, storageDirectory: String?) {
  NSFileManager.defaultManager.createDirectoryAtPath(
    applicationSupportDirectory(),
    withIntermediateDirectories = true,
    attributes = null,
    error = null,
  )
}

private fun applicationSupportDirectory(): String =
  NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first()
    as String
