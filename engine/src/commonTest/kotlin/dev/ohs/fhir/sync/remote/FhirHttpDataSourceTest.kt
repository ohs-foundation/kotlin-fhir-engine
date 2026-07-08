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
package dev.ohs.fhir.sync.remote

import dev.ohs.fhir.ContentTypes
import dev.ohs.fhir.NetworkConfiguration
import dev.ohs.fhir.model.r4.Base64Binary
import dev.ohs.fhir.model.r4.Binary
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirR4String
import dev.ohs.fhir.sync.upload.request.UrlUploadRequest
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.util.encodeBase64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

internal class FhirHttpDataSourceTest {
  private val baseUrl = "/baseR4/"
  private val parser = Json
  private var requestData: HttpRequestData? = null
  private val fhirHttpService by lazy {
    KtorHttpService.Builder(baseUrl, NetworkConfiguration())
      .build(
        engine =
          MockEngine { request ->
            requestData = request
            respondOk(
              content =
                parser.encodeToString(
                  Bundle(
                    id = "transaction-response-1",
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                  ),
                ),
            )
          },
      )
  }

  private lateinit var dataSource: FhirHttpDataSource

  @BeforeTest
  fun setup() {
    dataSource = FhirHttpDataSource(fhirHttpService)
  }

  @Test
  fun test_upload_with_UrlUploadRequest_POST() = runTest {
    val patient =
      Patient(
        id = "Patient-001",
        name =
          listOf(
            HumanName(
              given = listOf(FhirR4String(value = "John")),
              family = FhirR4String(value = "Doe"),
            ),
          ),
      )
    val request = UrlUploadRequest(Bundle.HTTPVerb.Post, "Patient", patient, emptyMap())
    dataSource.upload(request)

    requestData!!.url.encodedPath.shouldBeEqual("${baseUrl}${request.url}")
    requestData!!.method.shouldBeEqual(HttpMethod.Post)
    requestData!!.body.readBytearrayContentAsString().shouldBe(parser.encodeToString(patient))
  }

  @Test
  fun test_upload_with_UrlUploadRequest_PUT() = runTest {
    val patient =
      Patient(
        id = "Patient-001",
        name =
          listOf(
            HumanName(
              given = listOf(FhirR4String(value = "John")),
              family = FhirR4String(value = "Doe"),
            ),
          ),
      )
    val request =
      UrlUploadRequest(
        Bundle.HTTPVerb.Put,
        "Patient/Patient-001",
        patient,
        emptyMap(),
      )
    dataSource.upload(request)

    requestData!!.url.encodedPath.shouldBeEqual("${baseUrl}${request.url}")
    requestData!!.method.shouldBeEqual(HttpMethod.Put)
    requestData!!.body.readBytearrayContentAsString().shouldBe(parser.encodeToString(patient))
  }

  @Test
  fun test_upload_with_UrlUploadRequest_PATCH() = runTest {
    val patchToApply =
      Binary(
        data =
          Base64Binary(
            value =
              "[{\"op\":\"replace\",\"path\":\"\\/name\\/0\\/given\\/0\",\"value\":\"Janet\"}]"
                .encodeBase64(),
          ),
        contentType = Code(value = ContentTypes.APPLICATION_JSON_PATCH),
      )

    val request =
      UrlUploadRequest(
        Bundle.HTTPVerb.Patch,
        "Patient/Patient-001",
        patchToApply,
        mapOf("Content-Type" to ContentTypes.APPLICATION_JSON_PATCH),
      )
    dataSource.upload(request)

    requestData!!.url.encodedPath.shouldBeEqual("${baseUrl}${request.url}")
    requestData!!.method.shouldBeEqual(HttpMethod.Patch)
    requestData!!
      .body
      .readBytearrayContentAsString()
      .shouldBe("[{\"op\":\"replace\",\"path\":\"/name/0/given/0\",\"value\":\"Janet\"}]")
  }

  private fun OutgoingContent.readBytearrayContentAsString(): String? {
    return if (this is OutgoingContent.ByteArrayContent) {
      val bytes = this.bytes()
      bytes.decodeToString()
    } else {
      null
    }
  }
}
