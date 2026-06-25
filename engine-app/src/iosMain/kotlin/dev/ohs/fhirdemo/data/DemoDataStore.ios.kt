package dev.ohs.fhirdemo.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.ohs.fhir.sync.createDataStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
private val demoDataStore: DataStore<Preferences> by lazy {
    createDataStore {
        val docDir =
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            )
        requireNotNull(docDir).path + "/$demoDataStoreFileName"
    }
}

fun createDemoDataStore(): DataStore<Preferences> = demoDataStore