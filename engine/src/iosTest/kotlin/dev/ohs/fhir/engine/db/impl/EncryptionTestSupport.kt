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
import kotlin.reflect.KClass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual val encryptionTestSupport: EncryptionTestSupport? =
  object : EncryptionTestSupport {
    override fun deleteDatabaseFiles() {
      for (encrypted in listOf(false, true)) {
        NSFileManager.defaultManager.removeItemAtPath(
          databaseFileName(Unit, null, encrypted),
          error = null,
        )
      }
    }

    override fun readDatabaseHeader(encrypted: Boolean): ByteArray {
      val data = NSData.dataWithContentsOfFile(databaseFileName(Unit, null, encrypted))!!
      return ByteArray(16).also { bytes ->
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, 16u) }
      }
    }

    // The simulator test process has no Keychain, so the key is fixed here.
    override fun resetDatabaseKey() = useKey(1)

    override fun loseDatabaseKey() = useKey(2)

    private fun useKey(seed: Byte) {
      DatabaseEncryptionKeyProvider.keySourceForTesting = { ByteArray(32) { seed } }
    }

    override val keyMismatchException: KClass<out Throwable> = SQLiteException::class
  }
