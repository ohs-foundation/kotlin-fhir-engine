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

package dev.ohs.fhir.db.impl

import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.ohs.fhir.wasm.worker.createSqliteWasmDriver

internal actual fun getDatabaseBuilder(
  platformContext: Any,
): RoomDatabase.Builder<ResourceDatabase> {
  // The web driver persists to the Origin Private File System (OPFS) under this name, via a
  // SQLite-WASM Web Worker. Query coroutine context is left to Room's default (web is async).
  return Room.databaseBuilder<ResourceDatabase>(DATABASE_NAME)
    .setDriver(createSqliteWasmDriver())
}

private const val DATABASE_NAME = "resources.db"
