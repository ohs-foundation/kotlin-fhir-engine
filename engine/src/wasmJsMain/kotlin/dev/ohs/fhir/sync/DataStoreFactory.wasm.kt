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
package dev.ohs.fhir.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.WebLocalStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer

private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

/**
 * Returns a preferences [DataStore] backed by the browser's `localStorage` (persisted across page
 * reloads and shared across tabs).
 *
 * @param platformContext Ignored on web.
 * @param storageDirectory Namespaces the `localStorage` key. The browser has no directories, so it
 *   is used as a name prefix; this keeps a test store separate from the app's default store.
 */
internal actual fun getDataStore(
  platformContext: Any,
  storageDirectory: String?,
): DataStore<Preferences> {
  val name = storageDirectory?.let { "$it-$fhirDataStoreFileName" } ?: fhirDataStoreFileName
  return dataStores.getOrPut(name) {
    PreferenceDataStoreFactory.create(
      storage = WebLocalStorage(serializer = PreferencesSerializer, name = name),
    )
  }
}
