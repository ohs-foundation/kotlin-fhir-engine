/*
 * Copyright 2023 Google LLC
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

package dev.ohs.fhir.sync.remote


import dev.ohs.fhir.NetworkConfiguration
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import dev.ohs.fhir.model.r4.String as FhirR4String
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.Headers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test


class KtorHttpServiceTest {

  private val parser = FhirR4Json()

  @Test // https://github.com/google/android-fhir/issues/1892
  fun should_assemble_download_request_correctly() = runTest {
    // checks that a download request can be made successfully with parameters without exception
    var requestHeaders: Headers? = null
    val httpService = KtorHttpService.Builder("/", NetworkConfiguration())
      .build(engine = MockEngine { request ->
        requestHeaders = request.headers
        respondOk(parser.encodeToString(
          Patient(id = "patient-001", name = listOf(HumanName(given = listOf(FhirR4String(value = "John")),
            family = FhirR4String(value = "Doe"))))))
      })

    val result =
      httpService.get("Patient/patient-001", mapOf("If-Match" to "randomResourceVersionID"))
    requestHeaders!!.contains("If-Match", "randomResourceVersionID")
    // No exception should occur
    result.shouldBeInstanceOf<Patient>()
  }

  @Test // https://github.com/google/android-fhir/issues/1892
  fun should_assemble_upload_bundle_request_correctly() = runTest {
    // checks that a upload request can be made successfully with parameters without exception
    var requestHeaders: Headers? = null
    val httpService = KtorHttpService.Builder("/", NetworkConfiguration())
      .build(engine = MockEngine { request ->
        requestHeaders = request.headers
        respondOk(parser.encodeToString(
          Bundle(id = "transaction-response-1", type = Enumeration(value = Bundle.BundleType.Transaction_Response))
        ))
      })
    val request = Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

    val result =
      httpService.post(".", request, mapOf("If-Match" to "randomResourceVersionID"))
    requestHeaders!!.contains("If-Match", "randomResourceVersionID")
    // No exception has occurred
    result.shouldBeInstanceOf<Bundle>()
  }

  @Test
  fun should_use_fhir_converter_to_serialize_and_deserialize_request_and_response_for_fhir_resources() =
    runTest {
      val httpService = KtorHttpService.Builder("/", NetworkConfiguration())
        .build(engine = MockEngine { request ->
          respondOk(parser.encodeToString(
            Bundle(id = "transaction-response-1", type = Enumeration(value = Bundle.BundleType.Transaction_Response))
          ))
        })
      val request = Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

      val result = httpService.post(".", request, emptyMap())

      result.shouldBeInstanceOf<Bundle>()
      result.type.value.shouldBe(Bundle.BundleType.Transaction_Response)
      result.id.shouldBe("transaction-response-1")
    }
}
