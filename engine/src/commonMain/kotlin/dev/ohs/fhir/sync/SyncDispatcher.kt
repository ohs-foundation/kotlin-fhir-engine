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

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for background sync work. Resolves to [kotlinx.coroutines.Dispatchers.IO] on platforms
 * with a dedicated blocking-I/O thread pool (Android, Desktop, iOS). The browser has no such pool,
 * so the wasm actual uses [kotlinx.coroutines.Dispatchers.Default].
 */
expect val syncDispatcher: CoroutineDispatcher
