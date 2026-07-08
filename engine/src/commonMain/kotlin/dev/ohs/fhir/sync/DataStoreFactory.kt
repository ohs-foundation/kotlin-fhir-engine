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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

internal const val fhirDataStoreFileName = "fhir.engine.preferences_pb"

fun createDataStore(producePath: () -> String): DataStore<Preferences> =
  PreferenceDataStoreFactory.createWithPath(produceFile = { producePath().toPath() })

/**
 * @param storageDirectory Directory for the preferences file. Only honored on Desktop; ignored on
 *   Android/iOS which have an OS-provided app-scoped storage location. See
 *   [dev.ohs.fhir.FhirEngineConfiguration.storageDirectory].
 */
internal expect fun getDataStore(
  platformContext: Any,
  storageDirectory: String?,
): DataStore<Preferences>
