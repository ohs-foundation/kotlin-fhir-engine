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

package dev.ohs.fhir.db.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generates RFC 6902 JSON patches by comparing two JSON strings. This is a KMP-compatible
 * replacement for the JVM-only Jackson + jsonpatch library used in the original engine module.
 *
 * Filters out `/meta` and `/text` paths, matching the original engine behavior.
 */
internal object JsonDiff {

  private val ignorePaths = setOf("/meta", "/text")

  fun diff(source: String, target: String): String {
    val sourceElement = Json.parseToJsonElement(source)
    val targetElement = Json.parseToJsonElement(target)
    val ops = mutableListOf<JsonObject>()
    generateDiff("", sourceElement, targetElement, ops)
    val filtered =
      ops.filter { op ->
        val path = (op["path"] as? JsonPrimitive)?.content ?: ""
        ignorePaths.none { path.startsWith(it) }
      }
    return Json.encodeToString(JsonArray.serializer(), JsonArray(filtered))
  }

  private fun generateDiff(
    path: String,
    source: JsonElement,
    target: JsonElement,
    ops: MutableList<JsonObject>,
  ) {
    if (source == target) return

    when {
      source is JsonObject && target is JsonObject -> diffObjects(path, source, target, ops)
      source is JsonArray && target is JsonArray -> diffArrays(path, source, target, ops)
      else -> ops.add(replaceOp(path, target))
    }
  }

  private fun diffObjects(
    path: String,
    source: JsonObject,
    target: JsonObject,
    ops: MutableList<JsonObject>,
  ) {
    // Removed keys
    for (key in source.keys) {
      if (key !in target) {
        ops.add(removeOp("$path/${escapeJsonPointer(key)}"))
      }
    }
    // Added keys
    for (key in target.keys) {
      if (key !in source) {
        ops.add(addOp("$path/${escapeJsonPointer(key)}", target[key]!!))
      }
    }
    // Changed keys
    for (key in source.keys) {
      if (key in target) {
        generateDiff("$path/${escapeJsonPointer(key)}", source[key]!!, target[key]!!, ops)
      }
    }
  }

  private fun diffArrays(
    path: String,
    source: JsonArray,
    target: JsonArray,
    ops: MutableList<JsonObject>,
  ) {
    // Index-based array diff, matching fge json-patch (the library the original engine used):
    // recurse into elements at common indices, remove trailing source elements, append extra
    // target elements. This yields element-level paths like `/name/0/family`.
    val common = minOf(source.size, target.size)
    for (index in 0 until common) {
      generateDiff("$path/$index", source[index], target[index], ops)
    }
    // Source longer: remove the surplus tail. Each removal shifts the array down, so the index to
    // remove stays `common`.
    repeat(source.size - common) { ops.add(removeOp("$path/$common")) }
    // Target longer: append the surplus tail with the RFC 6902 end-of-array token.
    for (index in common until target.size) {
      ops.add(addOp("$path/-", target[index]))
    }
  }

  private fun replaceOp(path: String, value: JsonElement) = buildJsonObject {
    put("op", "replace")
    put("path", path)
    put("value", value)
  }

  private fun addOp(path: String, value: JsonElement) = buildJsonObject {
    put("op", "add")
    put("path", path)
    put("value", value)
  }

  private fun removeOp(path: String) = buildJsonObject {
    put("op", "remove")
    put("path", path)
  }

  private fun escapeJsonPointer(key: String) = key.replace("~", "~0").replace("/", "~1")
}
