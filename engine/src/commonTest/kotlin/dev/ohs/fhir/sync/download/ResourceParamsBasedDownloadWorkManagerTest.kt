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

package dev.ohs.fhir.sync.download

import dev.ohs.fhir.model.r4.Binary
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.OperationOutcome
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.sync.SyncDataParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeOneOf
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.to

class ResourceParamsBasedDownloadWorkManagerTest {

  @Test
  fun getNextRequestUrl_shouldReturnNextResourceUrls() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(
            ResourceType.Patient to mapOf("address-city" to "NAIROBI"),
            ResourceType.Immunization to emptyMap(),
            ResourceType.Observation to emptyMap(),
          ),
          TestResourceParamsBasedDownloadWorkManagerContext("2022-03-20"),
        )

      val urlsToDownload = mutableListOf<String>()
      do {
        val url = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
        if (url != null) {
          urlsToDownload.add(url)
        }
      } while (url != null)

      urlsToDownload shouldContainOnly listOf("Patient?address-city=NAIROBI&_sort=_lastUpdated&_lastUpdated=gt2022-03-20",
        "Observation?_sort=_lastUpdated&_lastUpdated=gt2022-03-20",
        "Immunization?_sort=_lastUpdated&_lastUpdated=gt2022-03-20",)
    }

  @Test
  fun getNextRequestUrl_shouldReturnResourceAndPageUrlsAsNextUrls() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(ResourceType.Patient to emptyMap(), ResourceType.Observation to emptyMap()),
          TestResourceParamsBasedDownloadWorkManagerContext("2022-03-20"),
        )

      val urlsToDownload = mutableListOf<String>()
      do {
        val url = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
        if (url != null) {
          urlsToDownload.add(url)
        }
        // Call process response so that It can add the next page url to be downloaded next.
        when (url) {
          "Patient?_sort=_lastUpdated&_lastUpdated=gt2022-03-20",
          "Observation?_sort=_lastUpdated&_lastUpdated=gt2022-03-20", -> {
            downloadManager.processResponse(
              Bundle(type = Enumeration(value = Bundle.BundleType.Searchset), link = listOf(
                Bundle.Link(url = Uri(value = "http://url-to-next-page?token=pageToken"), relation = dev.ohs.fhir.model.r4.String(value = "next"))
              ))
            )
          }
        }
      } while (url != null)

      urlsToDownload shouldContainOnly listOf("Patient?_sort=_lastUpdated&_lastUpdated=gt2022-03-20",
        "http://url-to-next-page?token=pageToken",
        "Observation?_sort=_lastUpdated&_lastUpdated=gt2022-03-20",
        "http://url-to-next-page?token=pageToken",)
    }

  @Test
  fun getNextRequestUrl_withLastUpdatedTimeProvidedInContext_ShouldAppendGtPrefixToLastUpdatedSearchParam() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(ResourceType.Patient to emptyMap()),
          TestResourceParamsBasedDownloadWorkManagerContext("2022-06-28"),
        )
      val url = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
        url shouldBe ("Patient?_sort=_lastUpdated&_lastUpdated=gt2022-06-28")
    }

  @Test
  fun getNextRequestUrl_withLastUpdatedSyncParamProvided_shouldReturnUrlWithExactProvidedLastUpdatedSyncParam() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(
            ResourceType.Patient to
              mapOf(
                SyncDataParams.LAST_UPDATED_KEY to "2022-06-28",
                SyncDataParams.SORT_KEY to "status",
              ),
          ),
          TestResourceParamsBasedDownloadWorkManagerContext("2022-07-07"),
        )
      val url = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
      url shouldBe "Patient?_lastUpdated=2022-06-28&_sort=status"
    }

  @Test
  fun getNextRequestUrl_withLastUpdatedSyncParamHavingGtPrefix_shouldReturnUrlWithExactProvidedLastUpdatedSyncParam() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(ResourceType.Patient to mapOf(SyncDataParams.LAST_UPDATED_KEY to "gt2022-06-28")),
          TestResourceParamsBasedDownloadWorkManagerContext("2022-07-07"),
        )
      val url = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
      url shouldBe "Patient?_lastUpdated=gt2022-06-28&_sort=_lastUpdated"
    }

  @Test
  fun getNextRequestUrl_withNullUpdatedTimeStamp_shouldReturnUrlWithoutLastUpdatedQueryParam() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(ResourceType.Patient to mapOf("address-city" to "NAIROBI")),
          NoOpResourceParamsBasedDownloadWorkManagerContext,
        )
      val actual = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
      actual shouldBe "Patient?address-city=NAIROBI&_sort=_lastUpdated"
    }

  @Test
  fun getNextRequestUrl_withEmptyUpdatedTimeStamp_shouldReturnUrlWithoutLastUpdatedQueryParam() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(ResourceType.Patient to mapOf("address-city" to "NAIROBI")),
          TestResourceParamsBasedDownloadWorkManagerContext(""),
        )
      val actual = downloadManager.getNextRequest()?.let { (it as UrlDownloadRequest).url }
      actual shouldBe "Patient?address-city=NAIROBI&_sort=_lastUpdated"
    }

  @Test
  fun get_summary_request_urls_should_return_resource_summary_urls() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          mapOf(
            ResourceType.Patient to mapOf("address-city" to "NAIROBI"),
            ResourceType.Immunization to emptyMap(),
            ResourceType.Observation to emptyMap(),
          ),
          TestResourceParamsBasedDownloadWorkManagerContext("2022-03-20"),
        )

      val urls = downloadManager.getSummaryRequestUrls()

      urls.map { it.key } shouldContainOnly listOf(ResourceType.Patient, ResourceType.Immunization, ResourceType.Observation)
      urls.map { it.value } shouldContainOnly listOf("Patient?address-city=NAIROBI&_sort=_lastUpdated&_lastUpdated=gt2022-03-20&_summary=count",
        "Immunization?_sort=_lastUpdated&_lastUpdated=gt2022-03-20&_summary=count",
        "Observation?_sort=_lastUpdated&_lastUpdated=gt2022-03-20&_summary=count",)
    }

  @Test
  fun process_response_should_throw_exception_including_diagnostics_from_operation_outcome() = runTest {
    val downloadManager =
      ResourceParamsBasedDownloadWorkManager(
        emptyMap(),
        NoOpResourceParamsBasedDownloadWorkManagerContext,
      )
    val response = OperationOutcome(issue = listOf(
      OperationOutcome.Issue(diagnostics = dev.ohs.fhir.model.r4.String(value = "Server couldn't fulfil the request."), code = Enumeration(value = OperationOutcome.IssueType.Exception), severity = Enumeration(value = OperationOutcome.IssueSeverity.Error))
    ))

    val exception = assertFailsWith<RuntimeException> {
      downloadManager.processResponse(response)
    }
    exception.message shouldBe "Server couldn't fulfil the request."
  }

  @Test
  fun process_response_should_return_empty_list_for_resource_that_is_not_a_bundle() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          emptyMap(),
          NoOpResourceParamsBasedDownloadWorkManagerContext,
        )
      val response = Binary(contentType = Code(value = "application/json"))

      downloadManager.processResponse(response).shouldBeEmpty()
    }

  @Test
  fun process_response_should_return_empty_list_for_bundle_that_is_not_a_search_set() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          emptyMap(),
          NoOpResourceParamsBasedDownloadWorkManagerContext,
        )
      val response = Bundle(type = Enumeration(value = Bundle.BundleType.Transaction_Response),
        entry = listOf(
          Bundle.Entry(resource = Patient(id = "Patient-Id-001")),
          Bundle.Entry(resource = Patient(id = "Patient-Id-002")),
        ))

      downloadManager.processResponse(response).shouldBeEmpty()
    }

  @Test
  fun process_response_should_return_resources_for_bundle_search_set() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          emptyMap(),
          NoOpResourceParamsBasedDownloadWorkManagerContext,
        )
      val response = Bundle(type = Enumeration(value = Bundle.BundleType.Searchset),
        entry = listOf(
          Bundle.Entry(resource = Patient(id = "Patient-Id-001")),
          Bundle.Entry(resource = Patient(id = "Patient-Id-002")),
        ))

      downloadManager.processResponse(response).map { it.id } shouldContainOnly listOf("Patient-Id-001", "Patient-Id-002")
    }

  @Test
  fun process_response_should_add_next_request() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          emptyMap(),
          NoOpResourceParamsBasedDownloadWorkManagerContext,
        )
      val response = Bundle(type = Enumeration(value = Bundle.BundleType.Searchset), link = listOf(
        Bundle.Link(url = Uri(value = "next_url"), relation = dev.ohs.fhir.model.r4.String(value = "next"))
      ))

      downloadManager.processResponse(response)

      downloadManager.getNextRequest() shouldBe DownloadRequest.of("next_url")
    }

  @Test
  fun process_response_should_not_add_next_request_if_next_url_is_missing() =
    runTest {
      val downloadManager =
        ResourceParamsBasedDownloadWorkManager(
          emptyMap(),
          NoOpResourceParamsBasedDownloadWorkManagerContext,
        )
      val response = Bundle(type = Enumeration(value = Bundle.BundleType.Searchset),
        entry = listOf(
          Bundle.Entry(resource = Patient(id = "Patient-Id-001")),
        ))

      downloadManager.processResponse(response)
      downloadManager.getNextRequest().shouldBeNull()
    }
}

val NoOpResourceParamsBasedDownloadWorkManagerContext =
  TestResourceParamsBasedDownloadWorkManagerContext(null)

class TestResourceParamsBasedDownloadWorkManagerContext(private val lastUpdatedTimeStamp: String?) :
  ResourceParamsBasedDownloadWorkManager.TimestampContext {
  override suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String?) {}

  override suspend fun getLasUpdateTimestamp(resourceType: ResourceType): String? =
    lastUpdatedTimeStamp
}
