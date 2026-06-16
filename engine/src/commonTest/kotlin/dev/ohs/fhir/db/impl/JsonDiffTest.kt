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

  // --- round-trip: applying the generated patch to source must reconstruct target ---

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

  // --- intentional /meta and /text filtering ---

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

  // --- helpers ---

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

  /** Minimal RFC 6902 apply supporting the op set JsonDiff emits (replace/add/remove). */
  private fun applyPatch(source: JsonElement, patch: JsonArray): JsonElement {
    var result = source
    for (opElement in patch) {
      val op = opElement.jsonObject
      val type = op["op"]!!.jsonPrimitive.content
      val segments = parsePointer(op["path"]!!.jsonPrimitive.content)
      result =
        when (type) {
          "replace",
          "add", -> setAtPath(result, segments, op["value"]!!)
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

  private fun setAtPath(
    element: JsonElement,
    segments: List<String>,
    value: JsonElement,
  ): JsonElement {
    if (segments.isEmpty()) return value
    val obj = element.jsonObject
    val key = segments.first()
    val child = obj[key] ?: JsonObject(emptyMap())
    val newChild = setAtPath(child, segments.drop(1), value)
    return JsonObject(obj.toMutableMap().apply { this[key] = newChild })
  }

  private fun removeAtPath(element: JsonElement, segments: List<String>): JsonElement {
    val obj = element.jsonObject
    val key = segments.first()
    return if (segments.size == 1) {
      JsonObject(obj.toMutableMap().apply { remove(key) })
    } else {
      val newChild = removeAtPath(obj.getValue(key), segments.drop(1))
      JsonObject(obj.toMutableMap().apply { this[key] = newChild })
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
