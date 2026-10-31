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
import dev.ohs.fhir.engine.db.DatabaseEncryptionException
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNSUPPORTED
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
        databaseFileName(platformContext, storageDirectory, config),
      )
    }
  return builder.setDriver(databaseDriver(config)).setQueryCoroutineContext(Dispatchers.IO)
}

internal actual val isDatabaseEncryptionSupported: Boolean = true

internal actual fun databaseDriver(config: DatabaseConfig): SQLiteDriver =
  if (config.encrypt) EncryptedDatabaseDriver(config.errorStrategy) else NativeSQLiteDriver()

/**
 * Keys every connection with the Keychain key. The app links SQLCipher, not the engine, so every
 * open first checks that it is linked.
 */
private class EncryptedDatabaseDriver(private val errorStrategy: DatabaseErrorStrategy) :
  SQLiteDriver {
  private val driver = NativeSQLiteDriver()

  // Read on the first open, as on Android, so a Keychain failure is thrown by the first database
  // call. Lazy reads it once for all of Room's connections.
  private val key by lazy {
    readKeyRetryingTimeouts({ NSThread.sleepForTimeInterval(it / 1000.0) }) {
      DatabaseEncryptionKeyProvider.getOrCreateKey()
    }
  }

  override fun open(fileName: String): SQLiteConnection =
    try {
      openKeyed(fileName)
    } catch (exception: SQLiteException) {
      // Recreate only an unreadable or corrupt file. Other errors, such as a locked database or a
      // full disk, are thrown so data that is still readable is kept.
      if (
        errorStrategy != DatabaseErrorStrategy.RECREATE_AT_OPEN || !exception.isUnreadableDatabase
      ) {
        throw exception
      }
      deleteDatabaseFiles(fileName)
      openKeyed(fileName)
    }

  // Checked on an in-memory database. Opening the file would create it, and an empty encrypted file
  // then stops the app from turning encryption off.
  private val sqlCipherLinked by lazy { driver.linksSqlCipher() }

  private fun openKeyed(fileName: String): SQLiteConnection {
    if (!sqlCipherLinked) {
      throw DatabaseEncryptionException(
        null,
        UNSUPPORTED,
        "SQLCipher is not linked into the app, so the database cannot be encrypted.",
      )
    }
    val connection = driver.open(fileName)
    try {
      connection.execSQL("PRAGMA key = \"x'${key.toHexString()}'\"")
      // Reads the header, which fails when the key does not match the file.
      connection.prepare("SELECT count(*) FROM sqlite_master").use { it.step() }
    } catch (exception: Throwable) {
      connection.close()
      throw exception
    }
    excludeFromBackup(fileName)
    return connection
  }

  // A restored copy cannot be read without the key, so the files are kept out of backups. The -wal
  // and -shm files only exist after the first write, so they are excluded on a later open.
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

/** Whether the SQLite the app links is SQLCipher. */
internal fun NativeSQLiteDriver.linksSqlCipher(): Boolean {
  val connection = open(":memory:")
  return try {
    connection.prepare("PRAGMA cipher_version").use { it.step() && it.getText(0).isNotEmpty() }
  } finally {
    connection.close()
  }
}

/**
 * Whether SQLite reported the file as not a database (26) or corrupt (11).
 *
 * The native [SQLiteException] has no code property. Its message starts with `Error code: N`, so
 * the code is parsed from there. Extended codes are on, so the primary code is the low byte.
 */
internal val SQLiteException.isUnreadableDatabase: Boolean
  get() {
    val code = message?.let { ERROR_CODE.find(it) }?.groupValues?.get(1)?.toInt() ?: return false
    return (code and 0xff) in setOf(SQLITE_CORRUPT, SQLITE_NOTADB)
  }

private val ERROR_CODE = Regex("""^Error code: (\d+)""")
private const val SQLITE_CORRUPT = 11
private const val SQLITE_NOTADB = 26

internal actual fun databaseFileName(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): String = "${applicationSupportDirectory()}/${config.fileName}"

internal actual fun databaseFileExists(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): Boolean =
  NSFileManager.defaultManager.fileExistsAtPath(
    databaseFileName(platformContext, storageDirectory, config),
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
