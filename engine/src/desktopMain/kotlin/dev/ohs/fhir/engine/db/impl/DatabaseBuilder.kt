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
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.engine.sync.defaultDesktopStorageDirectory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual fun getDatabaseBuilder(
  platformContext: Any,
  storageDirectory: String?,
): RoomDatabase.Builder<ResourceDatabase> {
  val dbDir = File(storageDirectory ?: defaultDesktopStorageDirectory)
  dbDir.mkdirs()
  val dbFile = File(dbDir, DATABASE_NAME)
  return Room.databaseBuilder<ResourceDatabase>(dbFile.absolutePath)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
}

private const val DATABASE_NAME = "resources.db"
