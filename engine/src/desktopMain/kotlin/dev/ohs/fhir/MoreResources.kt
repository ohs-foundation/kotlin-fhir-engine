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

@file:JvmName("MoreResourcesDesktop")

package dev.ohs.fhir

import com.google.fhir.model.r4.Resource
import kotlin.reflect.KClass

private const val KOTLIN_FHIR_R4_PACKAGE_PREFIX = "com.google.fhir.model.r4."

@Suppress("UNCHECKED_CAST")
actual fun <R : Resource> getResourceClass(resourceTypeName: String): KClass<R> {
  // Strip any curly-brace namespace prefix (e.g. "{http://hl7.org/fhir}Patient" -> "Patient")
  // mirroring the engine's CQL-engine workaround.
  val className = resourceTypeName.replace(Regex("\\{[^}]*\\}"), "")
  return Class.forName(KOTLIN_FHIR_R4_PACKAGE_PREFIX + className).kotlin as KClass<R>
}
