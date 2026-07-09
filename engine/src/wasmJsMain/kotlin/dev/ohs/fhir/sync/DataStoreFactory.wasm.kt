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
import androidx.datastore.preferences.core.Preferences

private var dataStoreInstance: DataStore<Preferences>? = null

/**
 * Returns the FHIR engine's preferences [DataStore] on web.
 *
 * The store is constructed keyed by [fhirDataStoreFileName] but is **not durably persisted** in the
 * browser — the path-based backing has no filesystem there. This is sufficient today because the
 * engine only reads or writes these preferences during sync, which is not wired up on web (see the
 * wasm `FhirSyncController` actual). Durable web persistence would use `WebLocalStorage`, available
 * in a newer DataStore release.
 *
 * @param platformContext Ignored on web.
 * @param storageDirectory Ignored on web; the browser has no filesystem directory.
 */
internal actual fun getDataStore(
  platformContext: Any,
  storageDirectory: String?,
): DataStore<Preferences> =
  dataStoreInstance ?: createDataStore { fhirDataStoreFileName }.also { dataStoreInstance = it }
