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

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.engine.DatabaseErrorStrategy
import java.io.File
import kotlinx.coroutines.Dispatchers
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

internal actual fun getDatabaseBuilder(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): RoomDatabase.Builder<ResourceDatabase> {
  val builder =
    if (config.inMemory) {
      Room.inMemoryDatabaseBuilder<ResourceDatabase>()
    } else {
      Room.databaseBuilder<ResourceDatabase>(
        platformContext as Context,
        databaseFileName(platformContext, storageDirectory, config.encrypt),
      )
    }
  return builder.setDriver(databaseDriver(config)).setQueryCoroutineContext(Dispatchers.IO)
}

internal actual val isDatabaseEncryptionSupported: Boolean = true

internal actual fun databaseDriver(config: DatabaseConfig): SQLiteDriver =
  if (config.encrypt) EncryptedDatabaseDriver(config.errorStrategy) else BundledSQLiteDriver()

/**
 * Opens the database through SQLCipher with the Keystore derived passphrase. The passphrase is
 * fetched on open, which Room runs off the main thread, so Keystore delays and retries stay there.
 */
private class EncryptedDatabaseDriver(private val errorStrategy: DatabaseErrorStrategy) :
  SQLiteDriver {
  init {
    System.loadLibrary("sqlcipher")
  }

  // One driver, so one pool. Built on the first open, which Room runs off the main thread. Loading
  // SQLCipher above is not deferred.
  private val driver by lazy { SQLCipherDriver(passphraseWithRetry(), null, null) }

  override fun open(fileName: String): SQLiteConnection {
    return try {
      driver.open(fileName)
    } catch (exception: SQLiteException) {
      // A database the current key cannot read is unrecoverable, so the caller may ask for a new
      // one.
      if (errorStrategy != DatabaseErrorStrategy.RECREATE_AT_OPEN) throw exception
      AndroidSQLiteDatabase.deleteDatabase(File(fileName))
      driver.open(fileName)
    }
  }

  // What SQLCipherDriver reports. Asking it would build the driver, which needs the deferred
  // passphrase.
  override val hasConnectionPool: Boolean
    get() = true

  private fun passphraseWithRetry(): ByteArray =
    readKeyRetryingTimeouts(Thread::sleep) {
      DatabaseEncryptionKeyProvider.getOrCreatePassphrase(DATABASE_PASSPHRASE_NAME)
    }
}

internal actual fun databaseFileName(
  platformContext: Any,
  storageDirectory: String?,
  encrypted: Boolean,
): String =
  (platformContext as Context)
    .getDatabasePath(if (encrypted) ENCRYPTED_DATABASE_NAME else DATABASE_NAME)
    .absolutePath

internal actual fun databaseFileExists(
  platformContext: Any,
  storageDirectory: String?,
  encrypted: Boolean,
): Boolean = File(databaseFileName(platformContext, storageDirectory, encrypted)).exists()

internal actual fun createDatabaseDirectory(platformContext: Any, storageDirectory: String?) {}
