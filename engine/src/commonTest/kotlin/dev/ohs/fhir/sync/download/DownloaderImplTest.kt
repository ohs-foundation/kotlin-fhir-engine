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

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.OperationOutcome
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.sync.DataSource
import dev.ohs.fhir.sync.DownloadWorkManager
import dev.ohs.fhir.sync.upload.request.UploadRequest
import io.kotest.matchers.collections.shouldContainInOrder
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DownloaderImplTest {

  @Test
  fun downloaderShouldDownloadAllTheRequestsEvenWhenSomeFail() = runTest {
    val downloadRequests =
      listOf(
        DownloadRequest.of("Patient"),
        DownloadRequest.of("Encounter"),
        DownloadRequest.of("Medication/med-123-that-fails"),
        DownloadRequest.of(bundleOf("Observation/ob-123", "Condition/con-123")),
      )

    val testDataSource: DataSource =
      object : DataSource {
        private fun download(path: String): Resource {
          return when (path) {
            "Patient" -> Bundle(type = Enumeration(value = Bundle.BundleType.Searchset), entry = listOf(
              Bundle.Entry(resource = Patient(id = "pa-123" ))
            ))
            "Encounter" -> Bundle(type = Enumeration(value = Bundle.BundleType.Searchset), entry = listOf(
              Bundle.Entry(resource = Encounter(id = "en-123", status = Enumeration(value = Encounter.EncounterStatus.Planned),
                `class` = Coding(code = Code(value = "AMB"), display = dev.ohs.fhir.model.r4.String(value = "ambulatory")),
                subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = "Patient/pa-123"))))
            ))
            "Medication/med-123-that-fails" -> OperationOutcome(issue = listOf(
              OperationOutcome.Issue(severity = Enumeration(value = OperationOutcome.IssueSeverity.Fatal),
                code = Enumeration(value = OperationOutcome.IssueType.Exception),
                diagnostics = dev.ohs.fhir.model.r4.String(value = "Resource not found."))
            ))
            else -> OperationOutcome(issue = listOf(
              OperationOutcome.Issue(severity = Enumeration(value = OperationOutcome.IssueSeverity.Error),
                code = Enumeration(value = OperationOutcome.IssueType.Invalid),
                diagnostics = dev.ohs.fhir.model.r4.String(value = "Unknown"))
            ))
          }
        }

        private fun download(bundle: Bundle): Resource {
          return Bundle(type = Enumeration(value = Bundle.BundleType.Batch_Response), entry = listOf(
            Bundle.Entry(resource = Observation(id = "ob-123", status = Enumeration(value = Observation.ObservationStatus.Registered),
              code = CodeableConcept(),
              subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = "Patient/pq-123"))
            )),
            Bundle.Entry(resource = Condition(id = "con-123", subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = "Patient/pq-123"))))
          ))
        }

        override suspend fun download(downloadRequest: DownloadRequest) =
          when (downloadRequest) {
            is UrlDownloadRequest -> download(downloadRequest.url)
            is BundleDownloadRequest -> download(downloadRequest.bundle)
          }

        override suspend fun upload(request: UploadRequest): Resource {
          throw UnsupportedOperationException()
        }
      }

    val downloader = DownloaderImpl(testDataSource, TestDownloadWorkManager(downloadRequests))

    val result = mutableListOf<Resource>()
    downloader.download().collectIndexed { _, value ->
      if (value is DownloadState.Success) {
        result.addAll(value.resources)
      }
    }

    result.map { it.id } shouldContainInOrder listOf("pa-123", "en-123", "ob-123", "con-123")
  }

  @Test
  fun downloaderShouldEmitAllTheStatesForRequestsWhetherTheyPassOrFail() =
    runTest {
      val downloadRequests =
        listOf(
          DownloadRequest.of("Patient"),
          DownloadRequest.of("Encounter"),
          DownloadRequest.of("Medication/med-123-that-fails"),
          DownloadRequest.of(bundleOf("Observation/ob-123", "Condition/con-123")),
        )

      val testDataSource: DataSource =
        object : DataSource {
          private fun download(path: String): Resource {
            return when (path) {
              "Patient" -> Bundle(type = Enumeration(value = Bundle.BundleType.Searchset), entry = listOf(
                Bundle.Entry(resource = Patient(id = "pa-123" ))
              ))
              "Encounter" -> Bundle(type = Enumeration(value = Bundle.BundleType.Searchset), entry = listOf(
                Bundle.Entry(resource = Encounter(id = "pa-123", status = Enumeration(value = Encounter.EncounterStatus.Planned),
                  `class` = Coding(code = Code(value = "AMB"), display = dev.ohs.fhir.model.r4.String(value = "ambulatory")),
                  subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = "Patient/pa-123"))))
              ))
              "Medication/med-123-that-fails" -> OperationOutcome(issue = listOf(
                OperationOutcome.Issue(severity = Enumeration(value = OperationOutcome.IssueSeverity.Fatal),
                  code = Enumeration(value = OperationOutcome.IssueType.Exception),
                  diagnostics = dev.ohs.fhir.model.r4.String(value = "Resource not found."))
              ))
              else -> OperationOutcome(issue = listOf(
                OperationOutcome.Issue(severity = Enumeration(value = OperationOutcome.IssueSeverity.Error),
                  code = Enumeration(value = OperationOutcome.IssueType.Invalid),
                  diagnostics = dev.ohs.fhir.model.r4.String(value = "Unknown"))
              ))
            }
          }

          private fun download(bundle: Bundle): Resource {
            return Bundle(type = Enumeration(value = Bundle.BundleType.Batch_Response), entry = listOf(
              Bundle.Entry(resource = Observation(id = "ob-123", status = Enumeration(value = Observation.ObservationStatus.Registered),
                code = CodeableConcept(),
                subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = "Patient/pq-123"))
              )),
              Bundle.Entry(resource = Condition(id = "con-123", subject = Reference(reference = dev.ohs.fhir.model.r4.String(value = "Patient/pq-123"))))
            ))
          }

          override suspend fun download(downloadRequest: DownloadRequest) =
            when (downloadRequest) {
              is UrlDownloadRequest -> download(downloadRequest.url)
              is BundleDownloadRequest -> download(downloadRequest.bundle)
            }

          override suspend fun upload(request: UploadRequest): Resource {
            throw UnsupportedOperationException()
          }
        }
      val downloader = DownloaderImpl(testDataSource, TestDownloadWorkManager(downloadRequests))

      val result = mutableListOf<DownloadState>()
      downloader.download().collectIndexed { _, value -> result.add(value) }

      result.map { it::class } shouldContainInOrder listOf(
        DownloadState.Started::class,
        DownloadState.Success::class,
        DownloadState.Success::class,
        DownloadState.Failure::class,
        DownloadState.Success::class,)

      result.filterIsInstance<DownloadState.Success>().map { it.completed } shouldContainInOrder listOf(1, 2, 4)
    }

  companion object {

    private fun bundleOf(vararg getRequest: String) =
      Bundle(type = Enumeration(value = Bundle.BundleType.Batch),
        entry = getRequest.map {
          Bundle.Entry(
            request = Bundle.Entry.Request(method = Enumeration(value = Bundle.HTTPVerb.Get), url = Uri(value = it))
          )
        })
  }
}

class TestDownloadWorkManager(downloadRequests: List<DownloadRequest>) : DownloadWorkManager {
  private val queue = ArrayDeque(downloadRequests)

  override suspend fun getNextRequest(): DownloadRequest? = queue.removeFirstOrNull()

  override suspend fun getSummaryRequestUrls() = emptyMap<ResourceType, String>()

  override suspend fun processResponse(response: Resource): Collection<Resource> {
    if (response is OperationOutcome) {
      throw RuntimeException(response.issue.firstOrNull()?.diagnostics?.value)
    }
    if (response is Bundle) {
      return response.entry.mapNotNull { it.resource }
    }
    return emptyList()
  }
}
