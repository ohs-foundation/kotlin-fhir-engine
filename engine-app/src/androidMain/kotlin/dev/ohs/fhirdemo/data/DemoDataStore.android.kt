package dev.ohs.fhirdemo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.ohs.fhir.sync.createDataStore

internal fun createDemoDataStore(context: Context): DataStore<Preferences> = createDataStore {
    context.filesDir.resolve(demoDataStoreFileName).absolutePath
}
