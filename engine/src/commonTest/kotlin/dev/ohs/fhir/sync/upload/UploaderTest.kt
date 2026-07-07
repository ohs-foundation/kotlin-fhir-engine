/*
 * Copyright 2022-2026 Open Health Stack Foundation
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
import dev.ohs.fhir.db.impl.dao.ForwardIncludeSearchResult
import dev.ohs.fhir.db.impl.dao.ReverseIncludeSearchResult
import dev.ohs.fhir.db.impl.entities.LocalChangeEntity
import dev.ohs.fhir.db.impl.entities.ResourceEntity
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.OperationOutcome
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.search.SearchQuery
import dev.ohs.fhir.sync.BundleDataSource
import dev.ohs.fhir.sync.UrlRequestDataSource
import dev.ohs.fhir.sync.upload.patch.PatchGenerator
import dev.ohs.fhir.sync.upload.patch.PatchGeneratorFactory
import dev.ohs.fhir.sync.upload.patch.PatchGeneratorMode
import dev.ohs.fhir.sync.upload.request.UploadRequestGeneratorFactory
import dev.ohs.fhir.sync.upload.request.UploadRequestGeneratorMode
import dev.ohs.fhir.toLocalChange
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

class UploaderTest {
  private lateinit var perResourcePatchGenerator: PatchGenerator
  private lateinit var perChangePatchGenerator: PatchGenerator

  @BeforeTest
  fun setUp() {
    perResourcePatchGenerator = PatchGeneratorFactory.byMode(PatchGeneratorMode.PerResource)
    perChangePatchGenerator = PatchGeneratorFactory.byMode(PatchGeneratorMode.PerChange)
  }

  @Test
  fun bundle_upload_for_per_resource_patch_should_output_responses_mapped_correctly_to_the_local_changes() =
    runTest {
      val updatedPatient1 =
        patient1.copy(
          name =
            listOf(
              HumanName(
                given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                family = dev.ohs.fhir.model.r4.String(value = "Nucleus"),
              ),
            ),
        )
      val result =
        Uploader(
            BundleDataSource {
              Bundle(
                type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                entry =
                  listOf(
                    Bundle.Entry(resource = updatedPatient1),
                    Bundle.Entry(resource = patient2),
                  ),
              )
            },
            perResourcePatchGenerator,
            bundleUploadRequestGenerator,
          )
          .upload(localChangesToTestSuccess, emptyList())
          .toList()

      // With BundleUploadRequestGenerator, all patches will be squashed into 1 request (default
      // bundleSize = 500). So only 1 result will be observed.
      result.shouldHaveSize(1)
      result.first().shouldBeInstanceOf<UploadRequestResult.Success>()
      with((result.first() as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(2)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(2)
          localChanges.all { it.resourceId == patient1Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
        with(this.last()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges.all { it.resourceId == patient2Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient2Id)
        }
      }
    }

  @Test
  fun bundle_upload_for_per_change_patch_should_output_responses_mapped_correctly_to_the_local_changes() =
    runTest {
      val updatedPatient1 =
        patient1.copy(
          name =
            listOf(
              HumanName(
                given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                family = dev.ohs.fhir.model.r4.String(value = "Nucleus"),
              ),
            ),
        )

      val result =
        Uploader(
            BundleDataSource {
              Bundle(
                type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                entry =
                  listOf(
                    Bundle.Entry(resource = patient1),
                    Bundle.Entry(resource = patient2),
                    Bundle.Entry(resource = updatedPatient1),
                  ),
              )
            },
            perChangePatchGenerator,
            bundleUploadRequestGenerator,
          )
          .upload(localChangesToTestSuccess, emptyList())
          .toList()

      // With BundleUploadRequestGenerator, all patches will be squashed into 1 request (default
      // bundleSize = 500). So only 1 result will be observed.
      result.shouldHaveSize(1)
      result.first().shouldBeInstanceOf<UploadRequestResult.Success>()
      with((result.first() as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(3)
        with(this[0]) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges[0].resourceId.shouldBe(patient1Id)
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
        with(this[1]) {
          localChanges.shouldHaveSize(1)
          localChanges.all { it.resourceId == patient2Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          (output as Patient).id.shouldBe(patient2Id)
        }
        with(this[2]) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges[0].resourceId.shouldBe(patient1Id)
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
      }
    }

  @Test
  fun bundle_upload_should_fail_if_bundle_response_has_incorrect_size() = runTest {
    val result =
      Uploader(
          BundleDataSource {
            Bundle(
              type = Enumeration(value = Bundle.BundleType.Transaction_Response),
            )
          },
          perResourcePatchGenerator,
          bundleUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun bundle_upload_should_fail_if_response_is_operation_outcome_with_issue() = runTest {
    val result =
      Uploader(
          BundleDataSource {
            OperationOutcome(
              issue =
                listOf(
                  OperationOutcome.Issue(
                    severity = Enumeration(value = OperationOutcome.IssueSeverity.Warning),
                    code = Enumeration(value = OperationOutcome.IssueType.Conflict),
                    diagnostics =
                      dev.ohs.fhir.model.r4.String(
                        value = "The resource has already been updated.",
                      ),
                  ),
                ),
            )
          },
          perResourcePatchGenerator,
          bundleUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun bundle_upload_should_fail_if_response_is_empty_operation_outcome() = runTest {
    val result =
      Uploader(
          BundleDataSource { OperationOutcome(issue = emptyList()) },
          perResourcePatchGenerator,
          bundleUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun bundle_upload_should_fail_if_response_is_neither_transaction_response_nor_operation_outcome() =
    runTest {
      val result =
        Uploader(
            BundleDataSource {
              Bundle(
                type = Enumeration(value = Bundle.BundleType.Searchset),
              )
            },
            perResourcePatchGenerator,
            bundleUploadRequestGenerator,
          )
          .upload(localChangesToTestFail, emptyList())
          .toList()

      result.shouldHaveSize(1)
      result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
    }

  @Test
  fun bundle_upload_should_fail_if_there_is_io_exception() = runTest {
    val result =
      Uploader(
          BundleDataSource { throw IOException("Failed to connect to server.") },
          perResourcePatchGenerator,
          bundleUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun url_upload_for_per_resource_patch_should_output_responses_mapped_correctly_to_the_local_changes() =
    runTest {
      val updatedPatient1 =
        patient1.copy(
          name =
            listOf(
              HumanName(
                given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                family = dev.ohs.fhir.model.r4.String(value = "Nucleus"),
              ),
            ),
        )

      val result =
        Uploader(
            UrlRequestDataSource {
              when (it.resource.id) {
                patient1Id -> updatedPatient1
                patient2Id -> patient2
                else -> throw IllegalArgumentException("Unknown patient ID")
              }
            },
            perResourcePatchGenerator,
            urlUploadRequestGenerator,
          )
          .upload(localChangesToTestSuccess, emptyList())
          .toList()

      // With UrlUploadRequestGenerator, patch-per-resource is mapped to one url request. So total
      // of 2 results will be observed.
      result.shouldHaveSize(2)
      result.all { it is UploadRequestResult.Success }.shouldBeTrue()
      with((result.first() as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(2)
          localChanges.all { it.resourceId == patient1Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
      }
      with((result.last() as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges.all { it.resourceId == patient2Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient2Id)
        }
      }
    }

  @Test
  fun url_upload_for_per_change_patch_should_output_responses_mapped_correctly_to_the_local_changes() =
    runTest {
      val updatedPatient1 =
        patient1.copy(
          name =
            listOf(
              HumanName(
                given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                family = dev.ohs.fhir.model.r4.String(value = "Nucleus"),
              ),
            ),
        )

      val result =
        Uploader(
            UrlRequestDataSource {
              when (it.httpVerb) {
                Bundle.HTTPVerb.Put -> {
                  when (it.resource.id) {
                    patient1Id -> updatedPatient1
                    patient2Id -> patient2
                    else -> throw IllegalArgumentException("Unknown patient ID")
                  }
                }
                Bundle.HTTPVerb.Patch -> updatedPatient1
                else -> throw IllegalArgumentException("Unknown patient ID")
              }
            },
            perChangePatchGenerator,
            urlUploadRequestGenerator,
          )
          .upload(localChangesToTestSuccess, emptyList())
          .toList()

      // With UrlUploadRequestGenerator, patch-per-resource is mapped to one url request. So total
      // of 2 results will be observed.
      result.shouldHaveSize(3)
      result.all { it is UploadRequestResult.Success }.shouldBeTrue()
      with((result[0] as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges[0].resourceId.shouldBe(patient1Id)
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
      }
      with((result[1] as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          localChanges.shouldHaveSize(1)
          localChanges.all { it.resourceId == patient2Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          (output as Patient).id.shouldBe(patient2Id)
        }
      }
      with((result[2] as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges[0].resourceId.shouldBe(patient1Id)
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
      }
    }

  @Test
  fun url_upload_should_fail_if_response_has_incorrect_resource_type() = runTest {
    val result =
      Uploader(
          UrlRequestDataSource {
            Bundle(
              type = Enumeration(value = Bundle.BundleType.Searchset),
            )
          },
          perResourcePatchGenerator,
          urlUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun url_upload_should_fail_if_response_is_operation_outcome_with_issue() = runTest {
    val result =
      Uploader(
          UrlRequestDataSource {
            OperationOutcome(
              issue =
                listOf(
                  OperationOutcome.Issue(
                    severity = Enumeration(value = OperationOutcome.IssueSeverity.Warning),
                    code = Enumeration(value = OperationOutcome.IssueType.Conflict),
                    diagnostics =
                      dev.ohs.fhir.model.r4.String(
                        value = "The resource has already been updated.",
                      ),
                  ),
                ),
            )
          },
          perResourcePatchGenerator,
          urlUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun url_upload_should_fail_if_response_is_empty_operation_outcome() = runTest {
    val result =
      Uploader(
          UrlRequestDataSource { OperationOutcome(issue = emptyList()) },
          perResourcePatchGenerator,
          urlUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun url_upload_should_fail_if_there_is_io_exception() = runTest {
    val result =
      Uploader(
          UrlRequestDataSource { throw IOException("Failed to connect to server.") },
          perResourcePatchGenerator,
          urlUploadRequestGenerator,
        )
        .upload(localChangesToTestFail, emptyList())
        .toList()

    result.shouldHaveSize(1)
    result.first().shouldBeInstanceOf<UploadRequestResult.Failure>()
  }

  @Test
  fun bundle_upload_for_per_resource_patch_with_bundle_size_1_should_output_responses_mapped_correctly_to_the_local_changes() =
    runTest {
      val updatedPatient1 =
        patient1.copy(
          name =
            listOf(
              HumanName(
                given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
                family = dev.ohs.fhir.model.r4.String(value = "Nucleus"),
              ),
            ),
        )

      val result =
        Uploader(
            BundleDataSource {
              when (it.resource.entry[0].resource?.id) {
                patient1Id ->
                  Bundle(
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                    entry = listOf(Bundle.Entry(resource = patient1)),
                  )
                patient2Id ->
                  Bundle(
                    type = Enumeration(value = Bundle.BundleType.Transaction_Response),
                    entry = listOf(Bundle.Entry(resource = patient2)),
                  )
                else -> throw IllegalArgumentException("Unknown patient ID")
              }
            },
            perResourcePatchGenerator,
            bundleUploadRequestGeneratorWithUnityBundleSize,
          )
          .upload(localChangesToTestSuccess, emptyList())
          .toList()

      // With BundleUploadRequestGenerator and bundleSize=1, each patch is mapped to a bundle
      // request. So we observe 2 results.
      result.shouldHaveSize(2)
      result.all { it is UploadRequestResult.Success }.shouldBeTrue()
      with((result.first() as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(2)
          localChanges.all { it.resourceId == patient1Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient1Id)
        }
      }
      with((result.last() as UploadRequestResult.Success).successfulUploadResponseMappings) {
        this.shouldHaveSize(1)
        with(this.first()) {
          this.shouldBeInstanceOf<ResourceUploadResponseMapping>()
          localChanges.shouldHaveSize(1)
          localChanges.all { it.resourceId == patient2Id }.shouldBeTrue()
          output.shouldBeInstanceOf<Patient>()
          output.id.shouldBe(patient2Id)
        }
      }
    }

  companion object {
    private val fhirR4Json = Json
    private val urlUploadRequestGenerator =
      UploadRequestGeneratorFactory.byMode(
        UploadRequestGeneratorMode.UrlRequest(Bundle.HTTPVerb.Put, Bundle.HTTPVerb.Patch),
      )
    private val bundleUploadRequestGenerator =
      UploadRequestGeneratorFactory.byMode(
        UploadRequestGeneratorMode.BundleRequest(Bundle.HTTPVerb.Put, Bundle.HTTPVerb.Patch),
      )

    private val bundleUploadRequestGeneratorWithUnityBundleSize =
      UploadRequestGeneratorFactory.byMode(
        UploadRequestGeneratorMode.BundleRequest(Bundle.HTTPVerb.Put, Bundle.HTTPVerb.Patch, 1),
      )

    const val patient1Id = "Patient-001"
    const val patient2Id = "Patient-002"
    val patient1 =
      Patient(
        id = patient1Id,
        name =
          listOf(
            HumanName(
              given = listOf(dev.ohs.fhir.model.r4.String(value = "John")),
              family = dev.ohs.fhir.model.r4.String(value = "Doe"),
            ),
          ),
      )

    val patient2 = Patient(id = patient2Id)

    @OptIn(ExperimentalUuidApi::class)
    val localChangesToTestFail =
      listOf(
        LocalChangeEntity(
            id = 1,
            resourceType = ResourceType.Patient.name,
            resourceUuid = Uuid.random(),
            resourceId = patient1Id,
            type = LocalChangeEntity.Type.INSERT.value,
            payload = fhirR4Json.encodeToString(patient1),
            timestamp = Clock.System.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(1)) },
      )

    val localChangesToTestSuccess =
      listOf(
        LocalChange(
          resourceType = ResourceType.Patient.name,
          resourceId = patient1Id,
          type = LocalChange.Type.INSERT,
          payload = fhirR4Json.encodeToString(patient1),
          timestamp = Clock.System.now(),
          versionId = null,
          token = LocalChangeToken(listOf(1)),
        ),
        LocalChange(
          resourceType = ResourceType.Patient.name,
          resourceId = patient2Id,
          type = LocalChange.Type.INSERT,
          payload = fhirR4Json.encodeToString(patient2),
          timestamp = Clock.System.now(),
          versionId = null,
          token = LocalChangeToken(listOf(2)),
        ),
        LocalChange(
          resourceType = ResourceType.Patient.name,
          resourceId = patient1Id,
          type = LocalChange.Type.UPDATE,
          payload = "[{\"op\":\"replace\",\"path\":\"/name/0/family\",\"value\":\"Nucleus\"}]",
          timestamp = Clock.System.now(),
          versionId = null,
          token = LocalChangeToken(listOf(3)),
        ),
      )
  }

  @OptIn(ExperimentalUuidApi::class)
  private val database: Database =
    object : Database {
      override suspend fun getLocalChangeResourceReferences(
        localChangeIds: List<Long>,
      ): List<LocalChangeResourceReference> {
        return emptyList()
      }

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
        lastUpdated: Instant?,
      ) {
        TODO("Not yet implemented")
      }

      override suspend fun updateResourcePostSync(
        oldResourceId: String,
        newResourceId: String,
        resourceType: ResourceType,
        versionId: String?,
        lastUpdated: Instant?,
      ) {
        TODO("Not yet implemented")
      }

      override suspend fun select(type: ResourceType, id: String): Resource {
        TODO("Not yet implemented")
      }

      override suspend fun selectEntity(
        type: ResourceType,
        id: String,
      ): ResourceEntity {
        TODO("Not yet implemented")
      }

      override suspend fun insertSyncedResources(resources: List<Resource>) {
        TODO("Not yet implemented")
      }

      override suspend fun delete(type: ResourceType, id: String) {
        TODO("Not yet implemented")
      }

      override suspend fun <R : Resource> search(query: SearchQuery): List<ResourceWithUUID<R>> {
        TODO("Not yet implemented")
      }

      override suspend fun count(query: SearchQuery): Long {
        TODO("Not yet implemented")
      }

      override suspend fun searchForwardReferencedResources(
        query: SearchQuery,
      ): List<ForwardIncludeSearchResult> {
        TODO("Not yet implemented")
      }

      override suspend fun searchReverseReferencedResources(
        query: SearchQuery,
      ): List<ReverseIncludeSearchResult> {
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
        updatedResource: Resource,
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

      override suspend fun getLocalChanges(type: ResourceType, id: String): List<LocalChange> {
        TODO("Not yet implemented")
      }

      override suspend fun getLocalChanges(resourceUuid: Uuid): List<LocalChange> {
        TODO("Not yet implemented")
      }

      override suspend fun purge(type: ResourceType, ids: Set<String>, forcePurge: Boolean) {
        TODO("Not yet implemented")
      }
    }
}
