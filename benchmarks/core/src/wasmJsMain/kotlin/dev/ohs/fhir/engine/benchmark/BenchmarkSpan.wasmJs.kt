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

internal actual fun performanceMark(name: String) = jsMark(name)

internal actual fun performanceMeasure(name: String, startMark: String) = jsMeasure(name, startMark)

private fun jsMark(name: String): Unit = js("performance.mark(name)")

private fun jsMeasure(name: String, startMark: String): Unit =
  js("performance.measure(name, startMark)")
