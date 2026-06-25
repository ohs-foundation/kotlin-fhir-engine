package dev.ohs.fhirdemo.data

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
