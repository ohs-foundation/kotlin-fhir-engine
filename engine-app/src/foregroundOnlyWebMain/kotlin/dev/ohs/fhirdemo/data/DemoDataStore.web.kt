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
package dev.ohs.fhirdemo.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.WebLocalStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer

// No filesystem on web: the demo app's own preferences (distinct from the FHIR engine's own
// datastore, see `engine`'s `DataStoreFactory.web.kt`) live in the browser's `localStorage`.
private val demoDataStore: DataStore<Preferences> by lazy {
  PreferenceDataStoreFactory.create(
    storage = WebLocalStorage(serializer = PreferencesSerializer, name = demoDataStoreFileName),
  )
}

fun createDemoDataStore(): DataStore<Preferences> = demoDataStore
