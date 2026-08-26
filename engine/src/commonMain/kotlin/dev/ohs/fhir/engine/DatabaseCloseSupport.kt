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
 * Whether [FhirEngineProvider.reset] may close the database on this platform.
 *
 * True everywhere except web, where closing terminates the SQLite Web Worker and leaves OPFS in a
 * state the next open never recovers from: every subsequent database call hangs instead of failing,
 * which takes the whole browser test session down with it. Web therefore keeps the connection open
 * across a reset, matching the behaviour before [FhirEngineProvider.reset] existed. Browser
 * benchmarks get a cold engine by reloading the page instead.
 */
internal expect fun canCloseDatabaseOnReset(): Boolean
