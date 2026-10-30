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

internal actual fun webTargetName(): String = "js"

internal actual fun webUserAgent(): String = kotlinx.browser.window.navigator.userAgent

internal actual fun webHardwareConcurrency(): Int? =
  (js("navigator.hardwareConcurrency") as? Number)?.toInt()

internal actual fun webTimelineMeasureCount(): Int =
  (js("performance.getEntriesByType('measure').length") as? Number)?.toInt() ?: 0
