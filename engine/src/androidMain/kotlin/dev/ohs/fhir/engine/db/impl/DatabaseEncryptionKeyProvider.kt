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

import android.os.Build
import android.security.KeyStoreException as AndroidKeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256
import android.security.keystore.KeyProperties.PURPOSE_SIGN
import androidx.annotation.VisibleForTesting
import dev.ohs.fhir.engine.db.DatabaseEncryptionException
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.TIMEOUT
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNKNOWN
import dev.ohs.fhir.engine.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNSUPPORTED
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Derives the database passphrase from a key that never leaves the Android Keystore, so the
 * passphrase is only ever held in memory.
 */
internal object DatabaseEncryptionKeyProvider {
  // Derived passphrases stay here for the life of the process, as in the android-fhir engine.
  // SQLCipher needs one on every open.
  private val passphrases = mutableMapOf<String, ByteArray>()

  /** Every failure is a [DatabaseEncryptionException], so callers handle one exception type. */
  @Synchronized
  fun getOrCreatePassphrase(keyName: String): ByteArray =
    passphrases.getOrPut(keyName) { derivePassphrase(keyName) }

  private fun derivePassphrase(keyName: String): ByteArray =
    try {
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_NAME).apply { load(null) }
      val signingKey =
        when (val stored = keyStore.getKey(keyName, null)) {
          null ->
            KeyGenerator.getInstance(KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE_NAME).run {
              init(KeyGenParameterSpec.Builder(keyName, PURPOSE_SIGN).build())
              generateKey()
            }
          is SecretKey -> stored
          // Replacing it would hide that something else owns the alias, so fail instead.
          else ->
            throw DatabaseEncryptionException(
              null,
              UNKNOWN,
              "Keystore entry $keyName is not a secret key.",
            )
        }
      Mac.getInstance(KEY_ALGORITHM_HMAC_SHA256).run {
        init(signingKey)
        doFinal(MESSAGE_TO_BE_SIGNED.toByteArray(Charsets.UTF_8))
      }
    } catch (exception: KeyStoreException) {
      throw exception.databaseEncryptionException
    } catch (exception: NoSuchAlgorithmException) {
      throw DatabaseEncryptionException(exception, UNSUPPORTED)
    } catch (exception: GeneralSecurityException) {
      throw DatabaseEncryptionException(exception, exception.wrappedKeystoreErrorCode)
    } catch (exception: IOException) {
      throw DatabaseEncryptionException(exception, UNKNOWN)
    } catch (exception: ProviderException) {
      throw DatabaseEncryptionException(exception, exception.wrappedKeystoreErrorCode)
    }

  @VisibleForTesting
  fun clearKeyCache() {
    passphrases.clear()
  }

  @VisibleForTesting const val ANDROID_KEYSTORE_NAME = "AndroidKeyStore"

  // Changing this message changes every passphrase and locks users out of their databases.
  private const val MESSAGE_TO_BE_SIGNED = "Android FHIR SDK rocks!"
}

// The Keystore reports its keymaster error code as a negative number in the exception message,
// with no API to read it. If that text changes, transient failures fall through to UNKNOWN and are
// no longer retried.
internal val KeyStoreException.databaseEncryptionException: DatabaseEncryptionException
  get() {
    val errorCode = message?.let { "-[0-9]+".toRegex().find(it)?.value?.toIntOrNull() }
    return DatabaseEncryptionException(
      this,
      when (errorCode) {
        in UNSUPPORTED_KEYMASTER_ERRORS -> UNSUPPORTED
        in TRANSIENT_KEYMASTER_ERRORS -> TIMEOUT
        else -> UNKNOWN
      },
    )
  }

// From API 33 key generation and signing failures wrap an android.security.KeyStoreException,
// which says whether a retry can help. Without this a busy Keystore there is never retried.
private val Throwable.wrappedKeystoreErrorCode: DatabaseEncryptionErrorCode
  get() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return UNKNOWN
    val failure =
      generateSequence(cause) { it.cause }
        .filterIsInstance<AndroidKeyStoreException>()
        .firstOrNull()
    return if (failure?.isTransientFailure == true) TIMEOUT else UNKNOWN
  }

private val UNSUPPORTED_KEYMASTER_ERRORS =
  setOf(
    -2, // ERROR_UNSUPPORTED_PURPOSE
    -4, // ERROR_UNSUPPORTED_ALGORITHM
    -6, // ERROR_UNSUPPORTED_KEY_SIZE
    -7, // ERROR_UNSUPPORTED_BLOCK_MODE
    -9, // ERROR_UNSUPPORTED_MAC_LENGTH
    -10, // ERROR_UNSUPPORTED_PADDING_MODE
    -12, // ERROR_UNSUPPORTED_DIGEST
    -17, // ERROR_UNSUPPORTED_KEY_FORMAT
    -19, // ERROR_UNSUPPORTED_KEY_ENCRYPTION_ALGORITHM
    -20, // ERROR_UNSUPPORTED_KEY_VERIFICATION_ALGORITHM
    -39, // ERROR_UNSUPPORTED_TAG
    -50, // ERROR_UNSUPPORTED_EC_FIELD
    -59, // ERROR_UNSUPPORTED_MIN_MAC_LENGTH
  )

private val TRANSIENT_KEYMASTER_ERRORS =
  setOf(
    -48, // ERROR_SECURE_HW_BUSY
    -49, // ERROR_SECURE_HW_COMMUNICATION_FAILED
  )
