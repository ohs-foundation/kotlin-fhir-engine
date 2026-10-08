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
package dev.ohs.fhir.engine.sync

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.LocalChangeToken
import dev.ohs.fhir.engine.OffsetDateTime
import dev.ohs.fhir.engine.SearchResult
import dev.ohs.fhir.engine.db.LocalChangeResourceReference
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.sync.download.BundleDownloadRequest
import dev.ohs.fhir.engine.sync.download.DownloadRequest
import dev.ohs.fhir.engine.sync.download.UrlDownloadRequest
import dev.ohs.fhir.engine.sync.upload.SyncUploadProgress
import dev.ohs.fhir.engine.sync.upload.UploadRequestResult
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import dev.ohs.fhir.engine.sync.upload.request.UploadRequest
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Instant as FhirInstant
import dev.ohs.fhir.model.r4.Meta
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** An engine with one pending local change, enough for a sync worker to run against. */
internal object TestFhirEngineImpl : FhirEngine {
  override suspend fun create(vararg resource: Resource) = emptyList<String>()

  override suspend fun get(type: ResourceType, id: String): Resource = Patient(id = id)

  override suspend fun update(vararg resource: Resource) {}

  override suspend fun delete(type: ResourceType, id: String) {}

  override suspend fun <R : Resource> search(search: Search): List<SearchResult<R>> = emptyList()

  @Deprecated("To be deprecated.")
  override suspend fun syncUpload(
    uploadStrategy: UploadStrategy,
    upload:
      suspend (List<LocalChange>, List<LocalChangeResourceReference>) -> Flow<UploadRequestResult>,
  ): Flow<SyncUploadProgress> = flow {
    emit(SyncUploadProgress(1, 1))
    upload(getLocalChanges(ResourceType.Patient, "123"), emptyList()).collect {
      when (it) {
        is UploadRequestResult.Success -> emit(SyncUploadProgress(0, 1))
        is UploadRequestResult.Failure -> emit(SyncUploadProgress(1, 1, it.uploadError))
      }
    }
  }

  @Deprecated("To be deprecated.")
  override suspend fun syncDownload(
    conflictResolver: ConflictResolver,
    download: suspend () -> Flow<List<Resource>>,
  ) {
    download().collect()
  }

  override suspend fun count(search: Search): Long = 0

  override suspend fun getLastSyncTimeStamp(): OffsetDateTime? = null

  override suspend fun clearDatabase() {}

  override suspend fun getLocalChanges(type: ResourceType, id: String): List<LocalChange> =
    listOf(
      LocalChange(
        resourceType = type.name,
        resourceId = id,
        payload = """{ "resourceType" : "${type.name}", "id" : "$id" }""",
        token = LocalChangeToken(listOf(1)),
        type = LocalChange.Type.INSERT,
        timestamp = Clock.System.now(),
      ),
    )

  override suspend fun purge(type: ResourceType, id: String, forcePurge: Boolean) {}

  override suspend fun purge(type: ResourceType, ids: Set<String>, forcePurge: Boolean) {}

  override suspend fun withTransaction(block: suspend FhirEngine.() -> Unit) {}
}

/** Answers every download with an empty bundle and every upload with one created patient. */
internal object TestDataSourceImpl : DataSource {
  override suspend fun download(downloadRequest: DownloadRequest): Resource =
    when (downloadRequest) {
      is UrlDownloadRequest -> Bundle(type = Enumeration(value = Bundle.BundleType.Searchset))
      is BundleDownloadRequest ->
        Bundle(type = Enumeration(value = Bundle.BundleType.Batch_Response))
    }

  override suspend fun upload(request: UploadRequest): Resource =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction_Response),
      entry = listOf(Bundle.Entry(resource = Patient(id = "123"))),
    )
}

/** Fails every request. The download error is larger than WorkManager can store as output. */
internal object TestFailingDatasource : DataSource {
  override suspend fun download(downloadRequest: DownloadRequest): Resource =
    when (downloadRequest) {
      is UrlDownloadRequest -> {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        throw Exception(
          (1..WORK_MANAGER_MAX_DATA_BYTES + 1).map { allowedChars.random() }.joinToString(""),
        )
      }
      is BundleDownloadRequest -> throw IllegalStateException("Posting Download Bundle failed...")
    }

  override suspend fun upload(request: UploadRequest): Resource {
    throw IllegalStateException("Posting Upload Bundle failed...")
  }

  // androidx.work.Data.MAX_DATA_BYTES, which this common source set cannot reference.
  private const val WORK_MANAGER_MAX_DATA_BYTES = 10 * 1024
}

internal open class TestDownloadManagerImpl(
  private val queries: List<String> = listOf("Patient?address-city=NAIROBI"),
) : DownloadWorkManager {
  private val urls = ArrayDeque(queries)

  override suspend fun getNextRequest(): DownloadRequest? =
    urls.removeFirstOrNull()?.let { DownloadRequest.of(it) }

  override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> =
    queries.associate { ResourceType.fromCode(it.substringBefore("?")) to "$it?_summary=count" }

  override suspend fun processResponse(response: Resource): Collection<Resource> =
    listOf(
      Patient(
        meta =
          Meta(
            lastUpdated =
              FhirInstant(value = FhirDateTime.fromString(Clock.System.now().toString())),
          ),
      ),
    )
}
