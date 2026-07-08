/*
 * Copyright 2025-2026 Open Health Stack Foundation
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
package dev.ohs.fhir.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException as DataStoreIOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class FhirDataStore(private val dataStore: DataStore<Preferences>) {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Observes the sync job terminal state for a given key and provides it as a Flow.
   *
   * @param key The key associated with the sync job.
   * @return A Flow of [LastSyncJobStatus] representing the terminal state of the sync job, or null
   *   if the state is not allowed.
   */
  fun observeTerminalSyncJobStatus(key: String): Flow<SyncJobStatus?> =
    dataStore.data
      .catch { e ->
        if (e !is DataStoreIOException) {
          throw e // rethrow all but androidx.datastore.core.IOException
        }
        Logger.e(e) { "Error reading FhirDataStore" }
        emit(emptyPreferences())
      }
      .map { prefs ->
        prefs[stringPreferencesKey(key)]?.let { json.decodeFromString<SyncJobStatus>(it) }
      }

  /**
   * Edits the DataStore to store synchronization job status. It creates a data object containing
   * the state type and serialized state of the synchronization job status. The edited preferences
   * are updated with the serialized data.
   *
   * @param key The key associated with the data to edit.
   * @param syncJobStatus The synchronization job status to be stored.
   */
  suspend fun writeTerminalSyncJobStatus(key: String, syncJobStatus: SyncJobStatus) {
    when (syncJobStatus) {
      is SyncJobStatus.Succeeded,
      is SyncJobStatus.Failed, -> writeStatus(key, syncJobStatus)
      else -> error("Cannot persist non-terminal sync status")
    }
  }

  suspend fun readLastSyncTimestamp(): Instant? =
    dataStore.data.first()[stringPreferencesKey(LAST_SYNC_TIMESTAMP_KEY)]?.let { Instant.parse(it) }

  suspend fun writeLastSyncTimestamp(timestamp: Instant) =
    dataStore.edit { it[stringPreferencesKey(LAST_SYNC_TIMESTAMP_KEY)] = timestamp.toString() }

  /** Stores the given unique-work-name in DataStore. */
  suspend fun storeUniqueWorkName(key: String, value: String) =
    dataStore.edit { it[stringPreferencesKey("$key-name")] = value }

  suspend fun removeUniqueWorkName(key: String) =
    dataStore.edit {
      val value = it.remove(stringPreferencesKey("$key-name"))
      Logger.d("Removed value: $value")
    }

  /** Fetches the stored unique-work-name from DataStore. */
  suspend fun fetchUniqueWorkName(key: String): String? =
    dataStore.data.first()[stringPreferencesKey("$key-name")]

  private suspend fun writeStatus(key: String, syncJobStatus: SyncJobStatus) {
    dataStore.edit { prefs ->
      prefs[stringPreferencesKey(key)] = json.encodeToString(syncJobStatus)
    }
  }

  companion object {
    private const val LAST_SYNC_TIMESTAMP_KEY = "LAST_SYNC_TIMESTAMP"
  }
}
