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
package dev.ohs.fhir.engine

/**
 * Value for [FhirEngineConfiguration.storageDirectory] to use in tests that go through
 * [FhirEngineProvider]. On Desktop, the engine's default storage directory (`~/.fhir-engine`) is
 * the same one a real running app on the same machine would use, so tests need an isolated
 * directory to avoid reading/clobbering that app's data (or vice versa). `null` elsewhere:
 * `storageDirectory` is ignored on Android/iOS, which already have OS-scoped, per-app storage that
 * a test process doesn't share with a real app.
 */
internal expect fun testStorageDirectory(): String?
