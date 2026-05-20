/*
 * Copyright 2025-2026 Google LLC
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

package dev.ohs.fhir

/**
 * iOS no-op: `org.fhir:ucum` is JVM-only and has no Kotlin/Native port. Quantity searches won't
 * normalize units on iOS (e.g. `5403 mg` won't match `5.403 g`).
 *
 * TODO(iOS UCUM): port canonical-form conversion to commonMain or wrap a native UCUM lib. JVM
 *   actual in androidMain/desktopMain is the spec to match.
 */
internal actual object UnitConverter {
  actual fun getCanonicalFormOrOriginal(value: UcumValue): UcumValue = value
}
