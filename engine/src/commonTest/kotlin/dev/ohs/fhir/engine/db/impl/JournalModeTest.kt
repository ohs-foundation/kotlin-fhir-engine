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

import androidx.room3.useReaderConnection
import androidx.sqlite.async.step
import dev.ohs.fhir.engine.testPlatformContext
import dev.ohs.fhir.engine.testStorageDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The engine relies on Room's default journal mode, WAL, so a search does not wait for a concurrent
 * write.
 */
class JournalModeTest {

  private var database: ResourceDatabase? = null

  @AfterTest
  fun tearDown() {
    database?.close()
  }

  // No spaces in the name: D8 cannot dex the lambdas' synthetic class names otherwise.
  @Test
  fun file_backed_database_opens_in_wal() = runTest {
    val opened =
      getDatabaseBuilder(
          platformContext = testPlatformContext(),
          storageDirectory = testStorageDirectory(),
          inMemory = false,
        )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
        .also { database = it }

    val mode =
      opened.useReaderConnection { transactor ->
        transactor.usePrepared("PRAGMA journal_mode") { statement ->
          if (statement.step()) statement.getText(0) else ""
        }
      }

    assertEquals("wal", mode.lowercase(), "reads stall behind a writer in any other mode")
  }
}
