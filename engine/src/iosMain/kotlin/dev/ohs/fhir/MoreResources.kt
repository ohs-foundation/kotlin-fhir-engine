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

import com.google.fhir.model.r4.Resource
import kotlin.reflect.KClass

/**
 * iOS stub: relies on `Class.forName` (JVM reflection) and has no Kotlin/Native equivalent.
 *
 * TODO(iOS getResourceClass): replace with a generated ResourceType -> KClass mapping when an iOS
 *   consumer needs it.
 */
actual fun <R : Resource> getResourceClass(resourceTypeName: String): KClass<R> =
  throw UnsupportedOperationException(
    "getResourceClass is JVM-only. Called with resourceTypeName=$resourceTypeName on iOS.",
  )
