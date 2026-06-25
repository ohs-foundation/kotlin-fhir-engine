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
package dev.ohs.fhir.sync.upload.request

import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.LocalChangeToken
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.sync.upload.patch.PatchMapping
import dev.ohs.fhir.sync.upload.patch.StronglyConnectedPatchMappings
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.deleteLocalChange
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.insertionLocalChange
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.toPatch
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.updateLocalChange
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class TransactionBundleGeneratorTest {

  @Test
  fun generate_upload_requests_should_return_empty_list_if_there_are_no_local_changes() = runTest {
    val generator = TransactionBundleGenerator.getDefault()
    val result = generator.generateUploadRequests(listOf())
    result.shouldBeEmpty()
  }

  @Test
  fun generate_upload_requests_should_return_single_transaction_bundle_with_3_entries() = runTest {
    val patches =
      listOf(insertionLocalChange, updateLocalChange, deleteLocalChange)
        .map { PatchMapping(listOf(it), it.toPatch()) }
        .map { StronglyConnectedPatchMappings(listOf(it)) }

    val generator = TransactionBundleGenerator.getDefault()
    val result = generator.generateUploadRequests(patches)

    result.shouldHaveSize(1)
    with(result[0].generatedRequest.resource) {
      type.value.shouldBe(Bundle.BundleType.Transaction)
      entry.shouldHaveSize(3)
      entry
        .mapNotNull { it.request?.method?.value }
        .shouldContainExactly(
          Bundle.HTTPVerb.Put,
          Bundle.HTTPVerb.Patch,
          Bundle.HTTPVerb.Delete,
        )
    }

    result[0].splitLocalChanges.shouldHaveSize(3)
    result[0].splitLocalChanges.all { it.size == 1 }.shouldBeTrue()
  }

  @Test
  fun generate_upload_requests_should_return_3_transaction_bundle_with_single_entry_each() =
    runTest {
      val patches =
        listOf(insertionLocalChange, updateLocalChange, deleteLocalChange)
          .map { PatchMapping(listOf(it), it.toPatch()) }
          .map { StronglyConnectedPatchMappings(listOf(it)) }
      val generator =
        TransactionBundleGenerator.getGenerator(
          Bundle.HTTPVerb.Put,
          Bundle.HTTPVerb.Patch,
          1,
          true,
        )
      with(generator.generateUploadRequests(patches)) {
        // Exactly 3 Requests are generated
        this.shouldHaveSize(3)
        // Each Request is of type Bundle
        all { it.generatedRequest.resource.type.value == Bundle.BundleType.Transaction }
          .shouldBeTrue()
        // Each Bundle has exactly 1 entry
        all { it.generatedRequest.resource.entry.size == 1 }.shouldBeTrue()
        map { it.generatedRequest.resource.entry.first().request?.method?.value }
          .shouldContainExactly(Bundle.HTTPVerb.Put, Bundle.HTTPVerb.Patch, Bundle.HTTPVerb.Delete)
        map { it.generatedRequest.resource.entry.first().request?.ifMatch?.value }
          .shouldContainExactly(null, "W/\"v-p002-01\"", "W/\"v-p003-01\"")
        // Each Bundle request is mapped to 1 LocalChange
        all { it.splitLocalChanges.size == 1 }.shouldBeTrue()
        all { it.splitLocalChanges[0].size == 1 }.shouldBeTrue()
      }
    }

  @Test
  fun get_generator_should_not_throw_exception_for_create_by_post() = runTest {
    val exception =
      runCatching {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Post,
            Bundle.HTTPVerb.Patch,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
        .exceptionOrNull()

    assertTrue(exception !is IllegalArgumentException, "IllegalArgumentException was thrown")
  }

  @Test
  fun generate_should_return_bundle_entry_without_if_match_when_use_etag_for_upload_is_false() =
    runTest {
      val localChange =
        LocalChange(
          resourceType = ResourceType.Patient.name,
          resourceId = "Patient-002",
          type = LocalChange.Type.UPDATE,
          payload = "[]",
          versionId = "patient-002-version-1",
          timestamp = Clock.System.now(),
          token = LocalChangeToken(listOf(1L)),
        )
      val patches =
        listOf(
            PatchMapping(
              localChanges = listOf(localChange),
              generatedPatch = localChange.toPatch(),
            ),
          )
          .map { StronglyConnectedPatchMappings(listOf(it)) }
      val generator = TransactionBundleGenerator.getDefault(useETagForUpload = false)
      val result = generator.generateUploadRequests(patches)

      (result.first().generatedRequest.resource.entry.first().request?.ifMatch?.value)
        .shouldBeNull()
      result.first().splitLocalChanges.shouldHaveSize(1)
      result.first().splitLocalChanges[0].shouldHaveSize(1)
    }

  @Test
  fun generate_should_return_bundle_entry_with_if_match_when_use_etag_for_upload_is_true() =
    runTest {
      val localChange =
        LocalChange(
          resourceType = ResourceType.Patient.name,
          resourceId = "Patient-002",
          type = LocalChange.Type.UPDATE,
          payload = "[]",
          versionId = "patient-002-version-1",
          timestamp = Clock.System.now(),
          token = LocalChangeToken(listOf(1L)),
        )
      val patches =
        listOf(
            PatchMapping(
              localChanges = listOf(localChange),
              generatedPatch = localChange.toPatch(),
            ),
          )
          .map { StronglyConnectedPatchMappings(listOf(it)) }
      val generator = TransactionBundleGenerator.getDefault(useETagForUpload = true)
      val result = generator.generateUploadRequests(patches)

      (result.first().generatedRequest.resource.entry.first().request?.ifMatch?.value).shouldBe(
        "W/\"patient-002-version-1\""
      )
      result.first().splitLocalChanges.shouldHaveSize(1)
      result.first().splitLocalChanges[0].shouldHaveSize(1)
    }

  @Test
  fun generate_should_return_bundle_entry_without_if_match_when_the_local_change_entity_has_no_version_id() =
    runTest {
      val localChanges =
        listOf(
          LocalChange(
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-002",
            type = LocalChange.Type.UPDATE,
            payload = "[]",
            versionId = "",
            timestamp = Clock.System.now(),
            token = LocalChangeToken(listOf(1L)),
          ),
          LocalChange(
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-003",
            type = LocalChange.Type.UPDATE,
            payload = "[]",
            versionId = null,
            timestamp = Clock.System.now(),
            token = LocalChangeToken(listOf(2L)),
          ),
        )
      val patches =
        localChanges
          .map { PatchMapping(listOf(it), it.toPatch()) }
          .map { StronglyConnectedPatchMappings(listOf(it)) }
      val generator = TransactionBundleGenerator.getDefault(useETagForUpload = true)
      val result = generator.generateUploadRequests(patches)

      (result.first().generatedRequest.resource.entry[0].request?.ifMatch?.value).shouldBeNull()
      (result.first().generatedRequest.resource.entry[1].request?.ifMatch?.value).shouldBeNull()
    }

  @Test
  fun get_generator_with_supported_bundle_httpverbs_should_return_transaction_bundle_generator() =
    runTest {
      val generator =
        TransactionBundleGenerator.getGenerator(
          Bundle.HTTPVerb.Put,
          Bundle.HTTPVerb.Patch,
          generatedBundleSize = 500,
          useETagForUpload = true,
        )

      generator.shouldBeInstanceOf<TransactionBundleGenerator>()
    }

  @Test
  fun get_generator_should_through_exception_for_create_by_delete() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Delete,
            Bundle.HTTPVerb.Patch,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Creation using DELETE is not supported.")
  }

  @Test
  fun get_generator_should_through_exception_for_create_by_get() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Get,
            Bundle.HTTPVerb.Patch,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Creation using GET is not supported.")
  }

  @Test
  fun get_generator_should_through_exception_for_create_by_patch() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Patch,
            Bundle.HTTPVerb.Patch,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Creation using PATCH is not supported.")
  }

  @Test
  fun get_generator_should_through_exception_for_update_by_delete() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Put,
            Bundle.HTTPVerb.Delete,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Update using DELETE is not supported.")
  }

  @Test
  fun get_generator_should_through_exception_for_update_by_get() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Put,
            Bundle.HTTPVerb.Get,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Update using GET is not supported.")
  }

  @Test
  fun get_generator_should_through_exception_for_update_by_post() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Put,
            Bundle.HTTPVerb.Post,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Update using POST is not supported.")
  }

  @Test
  fun get_generator_should_through_exception_for_update_by_put() {
    val exception =
      assertFailsWith(IllegalArgumentException::class) {
        runTest {
          TransactionBundleGenerator.getGenerator(
            Bundle.HTTPVerb.Put,
            Bundle.HTTPVerb.Put,
            generatedBundleSize = 500,
            useETagForUpload = true,
          )
        }
      }
    exception.message.shouldBe("Update using PUT is not supported.")
  }

  @Test
  fun generate_should_not_split_changes_in_multiple_bundle_if_combined_mapping_group_has_more_patches_than_the_permitted_size() =
    runTest {
      val localChange =
        LocalChange(
          resourceType = ResourceType.Patient.name,
          resourceId = "Patient-00",
          type = LocalChange.Type.UPDATE,
          payload = "[]",
          versionId = "patient-002-version-",
          timestamp = Clock.System.now(),
          token = LocalChangeToken(listOf(1L)),
        )
      val patchGroups =
        List(10) {
            PatchMapping(
              localChanges =
                listOf(
                  localChange.copy(
                    resourceId = "Patient-00-$it",
                    versionId = "patient-002-version-$it",
                  ),
                ),
              generatedPatch = localChange.toPatch(),
            )
          }
          .let { StronglyConnectedPatchMappings(it) }
      val generator =
        TransactionBundleGenerator.getDefault(useETagForUpload = false, bundleSize = 5)
      val result = generator.generateUploadRequests(listOf(patchGroups))

      result.shouldHaveSize(1)
      result.single().localChanges.shouldHaveSize(10)
    }

  @Test
  fun generate_should_put_group_mappings_in_respective_bundles() = runTest {
    val localChange =
      LocalChange(
        resourceType = ResourceType.Patient.name,
        resourceId = "Patient-00",
        type = LocalChange.Type.UPDATE,
        payload = "[]",
        versionId = "patient-002-version-",
        timestamp = Clock.System.now(),
        token = LocalChangeToken(listOf(1L)),
      )

    val firstGroup =
      StronglyConnectedPatchMappings(
        mutableListOf<PatchMapping>().apply {
          for (i in 1..5) {
            add(
              PatchMapping(
                localChanges =
                  listOf(
                    localChange.copy(
                      resourceId = "Patient-00-$i",
                      versionId = "patient-002-version-$i",
                    ),
                  ),
                generatedPatch = localChange.toPatch(),
              ),
            )
          }
        },
      )

    val secondGroup =
      StronglyConnectedPatchMappings(
        listOf(
          PatchMapping(
            localChanges =
              listOf(
                localChange.copy(resourceId = "Patient-00-6", versionId = "patient-002-version-7"),
              ),
            generatedPatch = localChange.toPatch(),
          ),
        ),
      )

    val thirdGroup =
      StronglyConnectedPatchMappings(
        listOf(
          PatchMapping(
            localChanges =
              listOf(
                localChange.copy(resourceId = "Patient-00-7", versionId = "patient-002-version-8"),
              ),
            generatedPatch = localChange.toPatch(),
          ),
        ),
      )
    val fourthGroup =
      StronglyConnectedPatchMappings(
        mutableListOf<PatchMapping>().apply {
          for (i in 9..13) {
            add(
              PatchMapping(
                localChanges =
                  listOf(
                    localChange.copy(
                      resourceId = "Patient-00-$i",
                      versionId = "patient-002-version-$i",
                    ),
                  ),
                generatedPatch = localChange.toPatch(),
              ),
            )
          }
        },
      )

    val patchGroups = listOf(firstGroup, secondGroup, thirdGroup, fourthGroup)
    val generator = TransactionBundleGenerator.getDefault(useETagForUpload = false, bundleSize = 5)
    val result = generator.generateUploadRequests(patchGroups)

    result.shouldHaveSize(3)
    result[0]
      .localChanges
      .map { it.resourceId }
      .shouldContainExactly(
        "Patient-00-1",
        "Patient-00-2",
        "Patient-00-3",
        "Patient-00-4",
        "Patient-00-5",
      )
    result[1]
      .localChanges
      .map { it.resourceId }
      .shouldContainExactly("Patient-00-6", "Patient-00-7")
    result[2]
      .localChanges
      .map { it.resourceId }
      .shouldContainExactly(
        "Patient-00-9",
        "Patient-00-10",
        "Patient-00-11",
        "Patient-00-12",
        "Patient-00-13",
      )
  }
}
