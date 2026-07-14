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
package dev.ohs.fhir.engine.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlinx.coroutines.flow.first

internal const val demoDataStoreFileName = "demo_app_storage.preferences_pb"

/**
 * Stores the lastUpdated timestamp per resource to be used by [DownloadWorkManager]'s
 * implementation for optimal sync. See
 * [_lastUpdated](https://build.fhir.org/search.html#_lastUpdated).
 */
class DemoDataStore(private val dataStorage: DataStore<Preferences>) {

  suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String) {
    dataStorage.edit { pref -> pref[stringPreferencesKey(resourceType.name)] = timestamp }
  }

  suspend fun getLastUpdateTimestamp(resourceType: ResourceType): String? {
    return dataStorage.data.first()[stringPreferencesKey(resourceType.name)]
  }
}
