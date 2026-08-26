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
package dev.ohs.fhir.engine.benchmark

/**
 * The test server is the only filesystem a browser run has: it serves the packaged dataset and the
 * run config, and receives the report. Split per target because Kotlin/Wasm has no `dynamic`.
 *
 * Both return null/false rather than throwing when nothing is listening, so a page served by
 * webpack rather than Karma degrades to defaults instead of failing.
 */
internal expect suspend fun httpGet(path: String): String?

internal expect suspend fun httpPost(path: String, body: String): Boolean
