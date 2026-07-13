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
package dev.ohs.fhir.engine.app.data

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.OperationOutcome
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.engine.resourceType
import dev.ohs.fhir.engine.sync.DownloadWorkManager
import dev.ohs.fhir.engine.sync.SyncDataParams
import dev.ohs.fhir.engine.sync.download.DownloadRequest
import kotlinx.datetime.toInstant

class TimestampBasedDownloadWorkManager(private val dataStore: DemoDataStore) :
  DownloadWorkManager {
  private val resourceTypeList = ResourceType.entries.map { it.name }
  private val urls = ArrayDeque(listOf("Patient?address-city=NAIROBI&_sort=_lastUpdated"))

  override suspend fun getNextRequest(): DownloadRequest? {
    var url = urls.removeFirstOrNull() ?: return null

    val resourceTypeToDownload =
      ResourceType.fromCode(url.findAnyOf(resourceTypeList, ignoreCase = true)!!.second)
    dataStore.getLastUpdateTimestamp(resourceTypeToDownload)?.let {
      url = affixLastUpdatedTimestamp(url, it)
    }
    return DownloadRequest.of(url)
  }

  override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> {
    return urls.associate {
      ResourceType.fromCode(it.substringBefore("?")) to
        it.plus("&${SyncDataParams.SUMMARY_KEY}=${SyncDataParams.SUMMARY_COUNT_VALUE}")
    }
  }

  override suspend fun processResponse(response: Resource): Collection<Resource> {
    // As per FHIR documentation :
    // If the search fails (cannot be executed, not that there are no matches), the
    // return value SHALL be a status code 4xx or 5xx with an OperationOutcome.
    // See https://www.hl7.org/fhir/http.html#search for more details.
    if (response is OperationOutcome) {
      throw RuntimeException(response.issue.first().diagnostics?.value)
    }

    // If the resource returned is a List containing Patients, extract Patient references and fetch
    // all resources related to the patient using the $everything operation.
    if (response is dev.ohs.fhir.model.r4.List) {
      for (entry in response.entry) {
        val reference = Reference(reference = entry.item.reference)
        if (reference.reference?.value?.substringBefore("/").equals("Patient")) {
          val patientUrl = "${entry.item.reference}/\$everything"
          urls.add(patientUrl)
        }
      }
    }

    // If the resource returned is a Bundle, check to see if there is a "next" relation referenced
    // in the Bundle.link component, if so, append the URL referenced to list of URLs to download.
    if (response is Bundle) {
      val nextUrl =
        response.link.firstOrNull { component -> component.relation.value == "next" }?.url?.value
      if (nextUrl != null) {
        urls.add(nextUrl)
      }
    }

    // Finally, extract the downloaded resources from the bundle.
    var bundleCollection: Collection<Resource> = mutableListOf()
    if (response is Bundle && response.type.value == Bundle.BundleType.Searchset) {
      bundleCollection =
        response.entry
          .mapNotNull { it.resource }
          .also { extractAndSaveLastUpdateTimestampToFetchFutureUpdates(it) }
    }
    return bundleCollection
  }

  private suspend fun extractAndSaveLastUpdateTimestampToFetchFutureUpdates(
    resources: List<Resource>,
  ) {
    resources
      .groupBy { it.resourceType }
      .entries
      .forEach { map ->
        dataStore.saveLastUpdatedTimestamp(
          ResourceType.valueOf(map.key),
          map.value
            .mapNotNull { it.meta?.lastUpdated?.value as? FhirDateTime.DateTime }
            .maxByOrNull { it.dateTime.toInstant(it.utcOffset).epochSeconds }
            ?.toString()
            ?: "",
        )
      }
  }
}

/**
 * Affixes the last updated timestamp to the request URL.
 *
 * If the request URL includes the `$everything` parameter, the last updated timestamp will be
 * attached using the `_since` parameter. Otherwise, the last updated timestamp will be attached
 * using the `_lastUpdated` parameter.
 */
private fun affixLastUpdatedTimestamp(url: String, lastUpdated: String): String {
  var downloadUrl = url

  // Affix lastUpdate to a $everything query using _since as per:
  // https://hl7.org/fhir/operation-patient-everything.html
  if (downloadUrl.contains("\$everything")) {
    downloadUrl = "$downloadUrl?_since=$lastUpdated"
  }

  // Affix lastUpdate to non-$everything queries as per:
  // https://hl7.org/fhir/operation-patient-everything.html
  if (!downloadUrl.contains("\$everything")) {
    downloadUrl = "$downloadUrl&_lastUpdated=gt$lastUpdated"
  }

  // Do not modify any URL set by a server that specifies the token of the page to return.
  if (downloadUrl.contains("&page_token")) {
    downloadUrl = url
  }

  return downloadUrl
}
