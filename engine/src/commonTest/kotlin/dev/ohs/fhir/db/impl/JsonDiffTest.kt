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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonDiffTest {

  @Test
  fun diff_changedPrimitive_roundTrips() {
    assertRoundTrip("""{"active":false}""", """{"active":true}""")
  }

  @Test
  fun diff_addedField_roundTrips() {
    assertRoundTrip("""{"id":"1"}""", """{"id":"1","active":true}""")
  }

  @Test
  fun diff_removedField_roundTrips() {
    assertRoundTrip("""{"id":"1","active":true}""", """{"id":"1"}""")
  }

  @Test
  fun diff_nestedObjectChange_roundTrips() {
    assertRoundTrip("""{"a":{"b":1,"c":2}}""", """{"a":{"b":9,"c":2}}""")
  }

  @Test
  fun diff_arrayElementChange_roundTrips() {
    assertRoundTrip(
      """{"name":[{"family":"A"}]}""",
      """{"name":[{"family":"B"}]}""",
    )
  }

  @Test
  fun diff_arrayElementAdded_roundTrips() {
    assertRoundTrip(
      """{"name":[{"family":"A"}]}""",
      """{"name":[{"family":"A"},{"family":"C"}]}""",
    )
  }

  @Test
  fun diff_arrayRemovedEntirely_roundTrips() {
    assertRoundTrip("""{"name":[{"family":"A"}]}""", """{}""")
  }

  @Test
  fun diff_arrayAdded_roundTrips() {
    assertRoundTrip("""{}""", """{"name":[{"family":"A"}]}""")
  }

  @Test
  fun diff_typeChange_roundTrips() {
    assertRoundTrip("""{"x":1}""", """{"x":"one"}""")
  }

  @Test
  fun diff_deepNestedWithArrays_roundTrips() {
    assertRoundTrip(
      """{"resourceType":"Patient","id":"p1","active":true,"name":[{"given":["John"],"family":"Doe"}],"contact":[{"name":{"family":"X"}}]}""",
      """{"resourceType":"Patient","id":"p1","active":false,"name":[{"given":["Jane","Q"],"family":"Roe"}],"contact":[{"name":{"family":"Y"}}],"gender":"female"}""",
    )
  }

  @Test
  fun diff_identical_producesEmptyPatch() {
    val json = """{"resourceType":"Patient","id":"1","active":true}"""
    assertEquals("[]", JsonDiff.diff(json, json))
  }

  @Test
  fun diff_arrayElementFieldChange_producesElementLevelPatch() {
    // Index-based array diff descends into elements, matching fge json-patch's documented output
    // for a family-name update (`/name/0/family`) rather than replacing the whole `/name` array.
    val patch =
      JsonDiff.diff(
        """{"name":[{"family":"Chalmers"}]}""",
        """{"name":[{"family":"Nucleus"}]}""",
      )
    assertEquals("""[{"op":"replace","path":"/name/0/family","value":"Nucleus"}]""", patch)
  }

  @Test
  fun diff_metaChange_isFilteredOut() {
    val patch =
      JsonDiff.diff(
        """{"meta":{"versionId":"1"},"active":true}""",
        """{"meta":{"versionId":"2"},"active":false}""",
      )
    // Only the /active change should survive; /meta is dropped.
    assertEquals("""[{"op":"replace","path":"/active","value":false}]""", patch)
  }

  @Test
  fun diff_textChange_isFilteredOut() {
    val patch =
      JsonDiff.diff(
        """{"text":{"status":"generated"},"active":true}""",
        """{"text":{"status":"additional"},"active":true}""",
      )
    assertEquals("[]", patch)
  }

  @Test
  fun diff_metaRemoval_isFilteredOut() {
    // Edited resource drops meta entirely (common when re-serializing an edited resource).
    val patch =
      JsonDiff.diff(
        """{"meta":{"versionId":"1"},"active":true}""",
        """{"active":false}""",
      )
    assertEquals("""[{"op":"replace","path":"/active","value":false}]""", patch)
  }

  /**
   * Asserts that applying `JsonDiff.diff(source, target)` to `source` reconstructs `target`. Use
   * only for cases without `/meta` or `/text` differences, since those are intentionally filtered.
   */
  private fun assertRoundTrip(source: String, target: String) {
    val patch = Json.parseToJsonElement(JsonDiff.diff(source, target)).jsonArray
    val applied = applyPatch(Json.parseToJsonElement(source), patch)
    assertEquals(
      Json.parseToJsonElement(target),
      applied,
      "patch $patch applied to $source did not yield $target",
    )
  }

  /**
   * Minimal RFC 6902 apply supporting the op set JsonDiff emits (replace/add/remove) over both
   * objects and arrays (paths can descend through array indices, e.g. `/name/0/family`).
   */
  private fun applyPatch(source: JsonElement, patch: JsonArray): JsonElement {
    var result = source
    for (opElement in patch) {
      val op = opElement.jsonObject
      val type = op["op"]!!.jsonPrimitive.content
      val segments = parsePointer(op["path"]!!.jsonPrimitive.content)
      result =
        when (type) {
          "replace" -> replaceAtPath(result, segments, op["value"]!!)
          "add" -> addAtPath(result, segments, op["value"]!!)
          "remove" -> removeAtPath(result, segments)
          else -> error("unsupported op $type")
        }
    }
    return result
  }

  private fun parsePointer(pointer: String): List<String> =
    if (pointer.isEmpty()) {
      emptyList()
    } else {
      pointer.removePrefix("/").split("/").map { it.replace("~1", "/").replace("~0", "~") }
    }

  /** Sets [value] at an existing [segments] path through objects and arrays. */
  private fun replaceAtPath(
    element: JsonElement,
    segments: List<String>,
    value: JsonElement,
  ): JsonElement {
    if (segments.isEmpty()) return value
    val head = segments.first()
    val rest = segments.drop(1)
    return when (element) {
      is JsonObject ->
        JsonObject(element.toMutableMap().apply { this[head] = replaceAtPath(getValue(head), rest, value) })
      is JsonArray ->
        JsonArray(element.toMutableList().apply { this[head.toInt()] = replaceAtPath(this[head.toInt()], rest, value) })
      else -> error("cannot descend into $element at $head")
    }
  }

  /** Adds [value] at [segments]: object key, array index, or array append (`-`). */
  private fun addAtPath(
    element: JsonElement,
    segments: List<String>,
    value: JsonElement,
  ): JsonElement {
    val head = segments.first()
    val rest = segments.drop(1)
    return when (element) {
      is JsonObject ->
        JsonObject(
          element.toMutableMap().apply {
            this[head] = if (rest.isEmpty()) value else addAtPath(getValue(head), rest, value)
          },
        )
      is JsonArray ->
        if (rest.isEmpty()) {
          if (head == "-") JsonArray(element + value)
          else JsonArray(element.toMutableList().apply { add(head.toInt(), value) })
        } else {
          JsonArray(element.toMutableList().apply { this[head.toInt()] = addAtPath(this[head.toInt()], rest, value) })
        }
      else -> error("cannot descend into $element at $head")
    }
  }

  private fun removeAtPath(element: JsonElement, segments: List<String>): JsonElement {
    val head = segments.first()
    val rest = segments.drop(1)
    return when (element) {
      is JsonObject ->
        if (rest.isEmpty()) JsonObject(element.toMutableMap().apply { remove(head) })
        else JsonObject(element.toMutableMap().apply { this[head] = removeAtPath(getValue(head), rest) })
      is JsonArray ->
        if (rest.isEmpty()) JsonArray(element.toMutableList().apply { removeAt(head.toInt()) })
        else JsonArray(element.toMutableList().apply { this[head.toInt()] = removeAtPath(this[head.toInt()], rest) })
      else -> error("cannot descend into $element at $head")
    }
  }

  @Test
  fun applyPatch_helper_isSane() {
    // Guards the test's own apply implementation.
    val result =
      applyPatch(
        Json.parseToJsonElement("""{"a":1,"b":2}"""),
        Json.parseToJsonElement("""[{"op":"replace","path":"/a","value":9}]""").jsonArray,
      )
    assertTrue(result.jsonObject["a"]!!.jsonPrimitive.content == "9")
  }
}
