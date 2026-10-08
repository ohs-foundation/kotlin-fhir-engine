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
)

/** Whether [DatabaseConfig.encrypt] can be honored on this platform. */
internal expect val isDatabaseEncryptionSupported: Boolean

/**
 * Returns a platform-specific [RoomDatabase.Builder] for [ResourceDatabase].
 *
 * @param platformContext Platform-specific context. On Android, this should be the application
 *   `Context`. On Desktop and iOS, this parameter is ignored.
 * @param storageDirectory Directory for the database file. Only honored on Desktop; ignored on
 *   Android/iOS which have an OS-provided app-scoped storage location. See
 *   [dev.ohs.fhir.engine.FhirEngineConfiguration.storageDirectory].
 * @param config In memory, encrypted or neither. [storageDirectory] is ignored in memory.
 */
internal expect fun getDatabaseBuilder(
  platformContext: Any,
  storageDirectory: String?,
  config: DatabaseConfig,
): RoomDatabase.Builder<ResourceDatabase>

/** The SQLite driver the engine's database uses on this platform for [config]. */
internal expect fun databaseDriver(config: DatabaseConfig): SQLiteDriver

/**
 * The file name (or, on web, the OPFS name) of the engine's database for [platformContext] and
 * [storageDirectory], the same one [getDatabaseBuilder] opens.
 */
internal expect fun databaseFileName(
  platformContext: Any,
  storageDirectory: String?,
  encrypted: Boolean = false,
): String

/** Whether the file [databaseFileName] names exists. Always false on web. */
internal expect fun databaseFileExists(
  platformContext: Any,
  storageDirectory: String?,
  encrypted: Boolean,
): Boolean

/** Creates the directory [databaseFileName] points into where the platform does not provide it. */
internal expect fun createDatabaseDirectory(platformContext: Any, storageDirectory: String?)
