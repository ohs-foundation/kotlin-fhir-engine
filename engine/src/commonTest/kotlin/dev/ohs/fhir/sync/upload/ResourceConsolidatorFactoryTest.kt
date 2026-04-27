/*
 * Copyright 2024 Google LLC
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

package dev.ohs.fhir.sync.upload


import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.LocalChangeToken
import dev.ohs.fhir.db.Database
import dev.ohs.fhir.db.LocalChangeResourceReference
import dev.ohs.fhir.db.ResourceWithUUID
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.search.ReferencedResourceResult
import dev.ohs.fhir.search.SearchQuery
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


class ResourceConsolidatorFactoryTest {
  @Test
  fun return_HttpPostResourceConsolidator_instance() {
    val httpPostResourceConsolidator =
      ResourceConsolidatorFactory.byHttpVerb(
        UploadStrategy.forIndividualRequest(
            methodForCreate = HttpCreateMethod.POST,
            methodForUpdate = HttpUpdateMethod.PATCH,
            squash = true,
          )
          .requestGeneratorMode,
        fakeDatabase,
      )
    assertTrue(httpPostResourceConsolidator is HttpPostResourceConsolidator)
  }

  @Test
  fun return_DefaultResourceConsolidator_instance() {
    val httpPostResourceConsolidator =
      ResourceConsolidatorFactory.byHttpVerb(
        UploadStrategy.forBundleRequest(
            methodForCreate = HttpCreateMethod.PUT,
            methodForUpdate = HttpUpdateMethod.PATCH,
            squash = true,
            bundleSize = 500,
          )
          .requestGeneratorMode,
        fakeDatabase,
      )
    assertTrue(httpPostResourceConsolidator is DefaultResourceConsolidator)
  }
}

@OptIn(ExperimentalUuidApi::class)
private val fakeDatabase = object : Database {
    override suspend fun <R : Resource> insert(vararg resource: R): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun <R : Resource> insertRemote(vararg resource: R) {
        TODO("Not yet implemented")
    }

    override suspend fun update(vararg resources: Resource) {
        TODO("Not yet implemented")
    }

    override suspend fun updateVersionIdAndLastUpdated(
        resourceId: String,
        resourceType: ResourceType,
        versionId: String?,
        lastUpdated: Instant?
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun updateResourcePostSync(
        oldResourceId: String,
        newResourceId: String,
        resourceType: ResourceType,
        versionId: String?,
        lastUpdated: Instant?
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun select(
        type: ResourceType,
        id: String
    ): Resource {
        TODO("Not yet implemented")
    }

    override suspend fun insertSyncedResources(resources: List<Resource>) {
        TODO("Not yet implemented")
    }

    override suspend fun delete(
        type: ResourceType,
        id: String
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun <R : Resource> search(query: SearchQuery): List<ResourceWithUUID<R>> {
        TODO("Not yet implemented")
    }

    override suspend fun count(query: SearchQuery): Long {
        TODO("Not yet implemented")
    }

    override suspend fun searchReferencedResources(query: SearchQuery): List<ReferencedResourceResult> {
        TODO("Not yet implemented")
    }

    override suspend fun getAllLocalChanges(): List<LocalChange> {
        TODO("Not yet implemented")
    }

    override suspend fun getAllChangesForEarliestChangedResource(): List<LocalChange> {
        TODO("Not yet implemented")
    }

    override suspend fun getLocalChangesCount(): Int {
        TODO("Not yet implemented")
    }

    override suspend fun deleteUpdates(token: LocalChangeToken) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteUpdates(resources: List<Resource>) {
        TODO("Not yet implemented")
    }

    override suspend fun updateResourceAndReferences(
        currentResourceId: String,
        updatedResource: Resource
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun withTransaction(block: suspend () -> Unit) {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override suspend fun clearDatabase() {
        TODO("Not yet implemented")
    }

    override suspend fun getLocalChanges(
        type: ResourceType,
        id: String
    ): List<LocalChange> {
        TODO("Not yet implemented")
    }

    override suspend fun getLocalChanges(resourceUuid: Uuid): List<LocalChange> {
        TODO("Not yet implemented")
    }

    override suspend fun purge(
        type: ResourceType,
        ids: Set<String>,
        forcePurge: Boolean
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun getLocalChangeResourceReferences(localChangeIds: List<Long>): List<LocalChangeResourceReference> {
        TODO("Not yet implemented")
    }
}
