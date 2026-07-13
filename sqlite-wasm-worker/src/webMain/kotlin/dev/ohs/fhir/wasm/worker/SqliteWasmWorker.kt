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
package dev.ohs.fhir.wasm.worker

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

// `WebWorkerSQLiteDriver` is a third-party `expect class` (androidx.sqlite), which Kotlin only
// allows instantiating from platform-specific code, not from a shared source set — so the
// constructor call itself, not just the Worker creation, has to be behind expect/actual.
/** Creates a [WebWorkerSQLiteDriver] backed by a SQLite-WASM Web Worker (OPFS-persisted). */
expect fun createSqliteWasmDriver(): WebWorkerSQLiteDriver
