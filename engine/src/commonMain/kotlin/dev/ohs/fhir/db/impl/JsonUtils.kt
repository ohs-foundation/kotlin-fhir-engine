/*
 * Copyright 2023-2026 Google LLC
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

package dev.ohs.fhir.db.impl

import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun addUpdatedReferenceToResource(
  resource: Resource,
  outdatedReference: String,
  updatedReference: String,
): Resource {
  val parser = FhirR4Json()
  val resourceJsonElement = Json.parseToJsonElement(parser.encodeToString(resource))
  val updatedResource = replaceJsonValue(resourceJsonElement, outdatedReference, updatedReference)
  return parser.decodeFromString(updatedResource.toString())
}

/**
 * Recursively replaces every string-valued [currentValue] with [newValue] in [element]. KMP
 * replacement for the org.json-based mutating overloads in the original engine module —
 * kotlinx.serialization elements are immutable, so this returns a new element instead.
 */
internal fun replaceJsonValue(
  element: JsonElement,
  currentValue: String,
  newValue: String,
): JsonElement =
  when (element) {
    is JsonObject ->
      JsonObject(
        element.mapValues { (_, value) -> replaceJsonValue(value, currentValue, newValue) },
      )
    is JsonArray -> JsonArray(element.map { replaceJsonValue(it, currentValue, newValue) })
    is JsonPrimitive ->
      if (element.isString && element.content == currentValue) JsonPrimitive(newValue) else element
  }

internal fun lookForReferencesInJsonPatch(jsonObject: JsonObject): String? {
  // "[{\"op\":\"replace\",\"path\":\"\\/basedOn\\/0\\/reference\",\"value\":\"CarePlan\\/345\"}]"
  val path = (jsonObject["path"] as? JsonPrimitive)?.content ?: return null
  return if (path.endsWith("reference")) (jsonObject["value"] as? JsonPrimitive)?.content else null
}

/**
 * Recursively collects every string value stored under [lookupKey] anywhere in [element]. KMP
 * replacement for the org.json-based `JSONObject`/`JSONArray` overloads in the original engine
 * module.
 */
internal fun extractAllValuesWithKey(lookupKey: String, element: JsonElement): List<String> =
  buildList {
    when (element) {
      is JsonObject ->
        element.forEach { (key, value) ->
          when (value) {
            is JsonObject -> addAll(extractAllValuesWithKey(lookupKey, value))
            is JsonArray -> addAll(extractAllValuesWithKey(lookupKey, value))
            is JsonPrimitive -> if (key == lookupKey) add(value.content)
          }
        }
      is JsonArray -> element.forEach { addAll(extractAllValuesWithKey(lookupKey, it)) }
      else -> {}
    }
  }
