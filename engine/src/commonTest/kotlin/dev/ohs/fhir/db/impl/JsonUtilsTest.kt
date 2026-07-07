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

import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class JsonUtilsTest {

  @Test
  fun addUpdatedReferenceToResource_updatesReferenceInPatient() {
    val patient =
      Patient(
        id = "f001",
        generalPractitioner = listOf(Reference(reference = FhirString(value = "Practitioner/123"))),
      )
    val updated =
      addUpdatedReferenceToResource(patient, "Practitioner/123", "Practitioner/345") as Patient
    assertEquals("Practitioner/345", updated.generalPractitioner.first().reference?.value)
  }

  @Test
  fun addUpdatedReferenceToResource_updatesMultipleReferenceInObservation() {
    val observation =
      Observation(
        id = "f001",
        status = Enumeration(value = Observation.ObservationStatus.Final),
        code = CodeableConcept(),
        subject = Reference(reference = FhirString(value = "Patient/123")),
        performer = listOf(Reference(reference = FhirString(value = "Patient/123"))),
      )
    val updated =
      addUpdatedReferenceToResource(observation, "Patient/123", "Patient/345") as Observation
    assertEquals("Patient/345", updated.subject?.reference?.value)
    assertEquals("Patient/345", updated.performer.first().reference?.value)
  }

  @Test
  fun replaceJsonValue_jsonObject1() {
    val input =
      """
      {"key1":"valueToBeReplaced","key2":{"key3":{"key4":["valueToBeReplaced","otherValueNotToBeReplaced"]}}}
      """
        .trimIndent()
    val expected =
      """
      {"key1":"newValue","key2":{"key3":{"key4":["newValue","otherValueNotToBeReplaced"]}}}
      """
        .trimIndent()
    assertEquals(
      Json.parseToJsonElement(expected),
      replaceJsonValue(Json.parseToJsonElement(input), "valueToBeReplaced", "newValue"),
    )
  }

  @Test
  fun replaceJsonValue_jsonObject2() {
    val input =
      """
      {"key1":"valueToBeReplaced","key2":{"key3":{"key4":[["otherValueNotToBeReplaced","valueToBeReplaced"],["otherValueNotToBeReplaced"]]}}}
      """
        .trimIndent()
    val expected =
      """
      {"key1":"newValue","key2":{"key3":{"key4":[["otherValueNotToBeReplaced","newValue"],["otherValueNotToBeReplaced"]]}}}
      """
        .trimIndent()
    assertEquals(
      Json.parseToJsonElement(expected),
      replaceJsonValue(Json.parseToJsonElement(input), "valueToBeReplaced", "newValue"),
    )
  }

  @Test
  fun replaceJsonValue_jsonObject3() {
    val input =
      """
      {"key1":"valueToBeReplaced","key2":{"key3":{"key4":[[{"key5":"valueToBeReplaced"}],[{"key6":"otherValueNotToBeReplaced"}]]}}}
      """
        .trimIndent()
    val expected =
      """
      {"key1":"newValue","key2":{"key3":{"key4":[[{"key5":"newValue"}],[{"key6":"otherValueNotToBeReplaced"}]]}}}
      """
        .trimIndent()
    assertEquals(
      Json.parseToJsonElement(expected),
      replaceJsonValue(Json.parseToJsonElement(input), "valueToBeReplaced", "newValue"),
    )
  }

  @Test
  fun extractAllValuesWithKey_extractsValuesFromJson() {
    val testJson =
      """
      {"key1":"newValue","reference":"testValue1","key2":{"key3":{"key4":[[{"reference":"testValue2"}],[{"key6":"otherValueNotToBeReplaced"}]]},"key5":{"reference":"testValue3"}}}
      """
        .trimIndent()
    val referenceValues = extractAllValuesWithKey("reference", Json.parseToJsonElement(testJson))
    assertEquals(3, referenceValues.size)
    assertEquals(setOf("testValue1", "testValue2", "testValue3"), referenceValues.toSet())
  }
}
