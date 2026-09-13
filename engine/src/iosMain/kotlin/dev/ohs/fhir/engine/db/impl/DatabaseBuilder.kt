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
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual fun getDatabaseBuilder(
  platformContext: Any,
  storageDirectory: String?,
  inMemory: Boolean,
): RoomDatabase.Builder<ResourceDatabase> {
  val builder =
    if (inMemory) {
      Room.inMemoryDatabaseBuilder<ResourceDatabase>()
    } else {
      createDatabaseDirectory(platformContext, storageDirectory)
      Room.databaseBuilder<ResourceDatabase>(databaseFileName(platformContext, storageDirectory))
    }
  return builder.setDriver(databaseDriver()).setQueryCoroutineContext(Dispatchers.IO)
}

internal actual fun databaseDriver(): SQLiteDriver = BundledSQLiteDriver()

internal actual fun databaseFileName(platformContext: Any, storageDirectory: String?): String =
  "${applicationSupportDirectory()}/$DATABASE_NAME"

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

private const val DATABASE_NAME = "resources.db"
