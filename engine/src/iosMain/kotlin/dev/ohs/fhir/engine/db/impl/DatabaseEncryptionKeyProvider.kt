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

import dev.ohs.fhir.engine.db.DatabaseEncryptionException
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.TIMEOUT
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNKNOWN
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Keeps a random database key in the Keychain. The key is readable after the first unlock of the
 * device so background sync can open the database, and it stays on this device, so a backup
 * restored elsewhere cannot read the database file.
 */
@OptIn(ExperimentalForeignApi::class)
internal object DatabaseEncryptionKeyProvider {
  // The simulator test process has no Keychain, so tests replace this. It is the only seam that
  // lets the driver be tested at all, and the key it returns stays in memory for the life of the
  // process either way.
  internal var keySourceForTesting: () -> ByteArray = { readKey() ?: createKey() }

  fun getOrCreateKey(): ByteArray = keySourceForTesting()

  // Only a missing item means there is no key yet. Any other status must not lead to a new key.
  private fun readKey(): ByteArray? = memScoped {
    val result = alloc<CFTypeRefVar>()
    val status = withQuery(kSecReturnData to kCFBooleanTrue) { SecItemCopyMatching(it, result.ptr) }
    when (status) {
      errSecSuccess -> (CFBridgingRelease(result.value) as NSData).toByteArray()
      errSecItemNotFound -> null
      else -> throw keychainFailure("read", status)
    }
  }

  private fun createKey(): ByteArray {
    val key = ByteArray(KEY_SIZE)
    key.usePinned {
      check(
        SecRandomCopyBytes(kSecRandomDefault, KEY_SIZE.toULong(), it.addressOf(0)) == errSecSuccess,
      )
    }
    val data = CFBridgingRetain(key.toNSData())
    val status =
      try {
        withQuery(
          kSecValueData to data,
          kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ) {
          SecItemAdd(it, null)
        }
      } finally {
        CFRelease(data)
      }
    return when (status) {
      errSecSuccess -> key
      // Another thread stored a key first. Both must use that one.
      errSecDuplicateItem -> readKey() ?: throw keychainFailure("store", status)
      else -> throw keychainFailure("store", status)
    }
  }

  private fun keychainFailure(action: String, status: Int) =
    DatabaseEncryptionException(
      null,
      // The Keychain is locked until the first unlock after a reboot. Callers can try again.
      if (status == errSecInteractionNotAllowed) TIMEOUT else UNKNOWN,
      "Could not $action the database key in the Keychain, status $status.",
    )

  /** Runs [block] with the Keychain query for the key entry and releases it afterwards. */
  private inline fun <T> withQuery(
    vararg attributes: Pair<CFTypeRef?, CFTypeRef?>,
    block: (CFDictionaryRef?) -> T,
  ): T {
    val service = CFBridgingRetain(SERVICE)
    val account = CFBridgingRetain(DATABASE_PASSPHRASE_NAME)
    val query = CFDictionaryCreateMutable(null, 0, null, null)
    try {
      CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
      CFDictionaryAddValue(query, kSecAttrService, service)
      CFDictionaryAddValue(query, kSecAttrAccount, account)
      attributes.forEach { (key, value) -> CFDictionaryAddValue(query, key, value) }
      return block(query)
    } finally {
      CFRelease(query)
      CFRelease(account)
      CFRelease(service)
    }
  }

  private fun NSData.toByteArray(): ByteArray =
    ByteArray(length.toInt()).also { bytes ->
      if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    }

  private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
  }

  private const val SERVICE = "dev.ohs.fhir.engine"
  private const val KEY_SIZE = 32
}
