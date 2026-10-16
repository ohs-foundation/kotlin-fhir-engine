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

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import dev.ohs.fhir.engine.DatabaseErrorStrategy

/** How the engine's database is opened, from [dev.ohs.fhir.engine.FhirEngineConfiguration]. */
internal data class DatabaseConfig(
  val inMemory: Boolean = false,
  val encrypt: Boolean = false,
  val errorStrategy: DatabaseErrorStrategy = DatabaseErrorStrategy.UNSPECIFIED,
) {
  /** The database file name, kept apart for the two settings so neither opens the other's file. */
  val fileName: String
    get() = if (encrypt) ENCRYPTED_DATABASE_NAME else DATABASE_NAME
}

/** Whether [DatabaseConfig.encrypt] can be honored on this platform. */
internal expect val isDatabaseEncryptionSupported: Boolean

/**
 * Returns a platform-specific [RoomDatabase.Builder] for [ResourceDatabase].
 *
 * @param platformContext The application `Context` on Android. Ignored elsewhere.
 * @param storageDirectory The database directory on desktop and a name prefix on web. Ignored on
 *   Android and iOS, which have an app-scoped location, and for an in-memory database. See
 *   [dev.ohs.fhir.engine.FhirEngineConfiguration.storageDirectory].
 */
internal expect fun getDatabaseBuilder(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): RoomDatabase.Builder<ResourceDatabase>

/** The SQLite driver the engine's database uses on this platform for [config]. */
internal expect fun databaseDriver(config: DatabaseConfig): SQLiteDriver

/** The file name, or on web the OPFS name, that [getDatabaseBuilder] opens for [config]. */
internal expect fun databaseFileName(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): String

/** Whether the file [databaseFileName] names exists. Always false on web. */
internal expect fun databaseFileExists(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): Boolean

/** Creates the directory [databaseFileName] points into where the platform does not provide it. */
internal expect fun createDatabaseDirectory(platformContext: Any, storageDirectory: String?)
