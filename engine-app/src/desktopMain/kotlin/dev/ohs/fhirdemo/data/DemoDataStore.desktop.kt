package dev.ohs.fhirdemo.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.ohs.fhir.sync.createDataStore
import java.io.File

fun createDemoDataStore(): DataStore<Preferences> = createDataStore {
    File(System.getProperty("user.home"), ".fhir-engine/$demoDataStoreFileName").absolutePath
}