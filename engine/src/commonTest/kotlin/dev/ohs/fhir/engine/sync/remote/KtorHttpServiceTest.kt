/*
 * Copyright 2023-2026 Open Health Stack Foundation
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
package dev.ohs.fhir.engine.sync.remote

import dev.ohs.fhir.engine.NetworkConfiguration
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirR4String
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.util.GZipEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class KtorHttpServiceTest {

  private val parser = Json

  @Test // https://github.com/google/android-fhir/issues/1892
  fun should_assemble_download_request_correctly() = runTest {
    // checks that a download request can be made successfully with parameters without exception
    var requestHeaders: Headers? = null
    val httpService =
      KtorHttpService.Builder("/", NetworkConfiguration())
        .build(
          engine =
            MockEngine { request ->
              requestHeaders = request.headers
              respondOk(
                parser.encodeToString(
                  Patient(
                    id = "patient-001",
                    name =
                      listOf(
                        HumanName(
                          given = listOf(FhirR4String(value = "John")),
                          family = FhirR4String(value = "Doe"),
                        ),
                      ),
                  ),
                ),
              )
            },
        )

    val result =
      httpService.get("Patient/patient-001", mapOf("If-Match" to "randomResourceVersionID"))
    requestHeaders!!["If-Match"] shouldBe "randomResourceVersionID"
    // No exception should occur
    result.shouldBeInstanceOf<Patient>()
  }

  @Test // https://github.com/google/android-fhir/issues/1892
  fun should_assemble_upload_bundle_request_correctly() = runTest {
    // checks that a upload request can be made successfully with parameters without exception
    var requestHeaders: Headers? = null
    val httpService =
      KtorHttpService.Builder("/", NetworkConfiguration())
        .build(
          engine =
            MockEngine { request ->
              requestHeaders = request.headers
              respondOk(
                parser.encodeToString(
                  Bundle(
                    id = "transaction-response-1",
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                  ),
                ),
              )
            },
        )
    val request =
      Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

    val result = httpService.post("", request, mapOf("If-Match" to "randomResourceVersionID"))
    requestHeaders!!["If-Match"] shouldBe "randomResourceVersionID"
    // No exception has occurred
    result.shouldBeInstanceOf<Bundle>()
  }

  @Test // https://github.com/ohs-foundation/kotlin-fhir-engine/issues/83
  fun should_post_bundle_to_base_url_without_appending_dot_segment() = runTest {
    var requestData: HttpRequestData? = null
    val httpService =
      KtorHttpService.Builder("https://example.com/fhir/", NetworkConfiguration())
        .build(
          engine =
            MockEngine { request ->
              requestData = request
              respondOk(
                parser.encodeToString(
                  Bundle(
                    id = "transaction-response-1",
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                  ),
                ),
              )
            },
        )
    val request =
      Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

    httpService.post("", request, emptyMap())

    requestData!!.url.encodedPath.shouldBeEqual("/fhir/")
  }

  @Test
  fun should_use_fhir_converter_to_serialize_and_deserialize_request_and_response_for_fhir_resources() =
    runTest {
      val httpService =
        KtorHttpService.Builder("/", NetworkConfiguration())
          .build(
            engine =
              MockEngine { request ->
                respondOk(
                  parser.encodeToString(
                    Bundle(
                      id = "transaction-response-1",
                      type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                    ),
                  ),
                )
              },
          )
      val request =
        Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

      val result = httpService.post("", request, emptyMap())

      result.shouldBeInstanceOf<Bundle>()
      result.type.value.shouldBe(Bundle.BundleType.Transaction_Response)
      result.id.shouldBe("transaction-response-1")
    }

  @Test
  fun should_gzip_upload_body_when_uploadWithGzip_enabled() = runTest {
    var sentBody: ByteArray? = null
    var sentContentEncoding: String? = null
    val httpService =
      KtorHttpService.Builder("/", NetworkConfiguration(uploadWithGzip = true))
        .build(
          engine =
            MockEngine { request ->
              sentBody = request.body.toByteArray()
              sentContentEncoding =
                request.body.headers[HttpHeaders.ContentEncoding]
                  ?: request.headers[HttpHeaders.ContentEncoding]
              respondOk(
                parser.encodeToString(
                  Bundle(
                    id = "transaction-response-1",
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                  ),
                ),
              )
            },
        )
    val request =
      Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

    httpService.post("", request, emptyMap())

    val body = sentBody!!
    if (supportsRequestCompression()) {
      sentContentEncoding.shouldBe("gzip")
      body[0].shouldBe(0x1f.toByte())
      body[1].shouldBe(0x8b.toByte())
      gunzip(body).shouldBe(parser.encodeToString<Resource>(request))
    } else {
      sentContentEncoding.shouldBe(null)
      body.decodeToString().startsWith("{").shouldBe(true)
    }
  }

  @Test
  fun should_not_gzip_upload_body_by_default() = runTest {
    var sentBody: ByteArray? = null
    var sentContentEncoding: String? = null
    val httpService =
      KtorHttpService.Builder("/", NetworkConfiguration())
        .build(
          engine =
            MockEngine { request ->
              sentBody = request.body.toByteArray()
              sentContentEncoding =
                request.body.headers[HttpHeaders.ContentEncoding]
                  ?: request.headers[HttpHeaders.ContentEncoding]
              respondOk(
                parser.encodeToString(
                  Bundle(
                    id = "transaction-response-1",
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                  ),
                ),
              )
            },
        )
    val request =
      Bundle(id = "transaction-1", type = Enumeration(value = Bundle.BundleType.Transaction))

    httpService.post("", request, emptyMap())

    sentContentEncoding.shouldBe(null)
    sentBody!!.decodeToString().startsWith("{").shouldBe(true)
  }

  @Test
  fun gzippedUpload_setsContentLength() = runTest {
    if (!supportsRequestCompression()) return@runTest
    var sent: HttpRequestData? = null
    var sentBody: ByteArray? = null
    gzipHttpService { request ->
        sent = request
        sentBody = request.body.toByteArray()
      }
      .post("", Patient(id = "patient-1"), emptyMap())

    sent!!.body.contentLength.shouldBe(sentBody!!.size.toLong())
    sent!!.body.contentType.shouldBe(ContentType.Application.Json)
  }

  @Test
  fun gzippedPatch_keepsJsonPatchContentType() = runTest {
    if (!supportsRequestCompression()) return@runTest
    var sent: HttpRequestData? = null
    gzipHttpService { sent = it }.patch("Patient/patient-1", JsonArray(emptyList()), emptyMap())

    sent!!.body.contentType.toString().shouldContain("application/json-patch+json")
  }

  @Test
  fun gzipEnabled_decompressesGzippedResponse() = runTest {
    if (!supportsRequestCompression()) return@runTest
    val gzippedPatient =
      gzip(parser.encodeToString<Resource>(Patient(id = "patient-1")).encodeToByteArray())
    val httpService =
      KtorHttpService.Builder("/", NetworkConfiguration(uploadWithGzip = true))
        .build(
          engine =
            MockEngine {
              respond(
                ByteReadChannel(gzippedPatient),
                headers = headersOf(HttpHeaders.ContentEncoding, "gzip"),
              )
            },
        )

    httpService.get("Patient/patient-1", emptyMap()).id.shouldBe("patient-1")
  }

  @Test
  fun gzippedUpload_appendsGzipToAnExistingContentEncoding() = runTest {
    if (!supportsRequestCompression()) return@runTest
    var sent: HttpRequestData? = null
    gzipHttpService { sent = it }
      .post("", Patient(id = "patient-1"), mapOf(HttpHeaders.ContentEncoding to "deflate"))

    sent!!.headers.getAll(HttpHeaders.ContentEncoding).shouldBe(listOf("deflate", "gzip"))
  }

  private suspend fun gunzip(bytes: ByteArray) =
    GZipEncoder.decode(ByteReadChannel(bytes)).toByteArray().decodeToString()

  /** A service with gzip uploads on, passing each request to [onRequest]. */
  private fun gzipHttpService(onRequest: suspend (HttpRequestData) -> Unit) =
    KtorHttpService.Builder("/", NetworkConfiguration(uploadWithGzip = true))
      .build(
        engine =
          MockEngine { request ->
            onRequest(request)
            respondOk(parser.encodeToString(Patient(id = "patient-1")))
          },
      )
}
