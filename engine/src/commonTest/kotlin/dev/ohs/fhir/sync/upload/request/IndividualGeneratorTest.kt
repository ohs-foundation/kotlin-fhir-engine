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

import dev.ohs.fhir.model.r4.Binary
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.sync.upload.patch.PatchMapping
import dev.ohs.fhir.sync.upload.patch.StronglyConnectedPatchMappings
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.deleteLocalChange
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.insertionLocalChange
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.toPatch
import dev.ohs.fhir.sync.upload.request.RequestGeneratorTestUtils.updateLocalChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.ktor.util.decodeBase64String
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class IndividualGeneratorTest {

  @Test
  fun should_return_empty_list_if_there_are_no_local_changes() = runTest {
    val generator = UrlRequestGenerator.getDefault()
    val requests = generator.generateUploadRequests(listOf())
    requests.shouldBeEmpty()
  }

  @Test
  fun should_create_a_post_request_for_insert() = runTest {
    val generator = UrlRequestGenerator.getGenerator(Bundle.HTTPVerb.Post, Bundle.HTTPVerb.Patch)
    val patchOutput =
      PatchMapping(
        localChanges = listOf(insertionLocalChange),
        generatedPatch = insertionLocalChange.toPatch(),
      )
    val requests =
      generator.generateUploadRequests(
        listOf(StronglyConnectedPatchMappings(listOf(patchOutput))),
      )

    with(requests.single()) {
      with(generatedRequest) {
        httpVerb.shouldBeEqual(Bundle.HTTPVerb.Post)
        url.shouldBeEqual("Patient")
      }
      localChanges.shouldBeEqual(patchOutput.localChanges)
    }
  }

  @Test
  fun should_create_a_put_request_for_insert() = runTest {
    val generator = UrlRequestGenerator.getDefault()
    val patchOutput =
      PatchMapping(
        localChanges = listOf(insertionLocalChange),
        generatedPatch = insertionLocalChange.toPatch(),
      )
    val requests =
      generator.generateUploadRequests(
        listOf(StronglyConnectedPatchMappings(listOf(patchOutput))),
      )

    with(requests.single()) {
      with(generatedRequest) {
        httpVerb.shouldBeEqual(Bundle.HTTPVerb.Put)
        url.shouldBeEqual("Patient/Patient-001")
      }
      localChanges.shouldBeEqual(patchOutput.localChanges)
    }
  }

  @Test
  fun should_create_a_patch_request_for_update() = runTest {
    val patchOutput =
      PatchMapping(
        localChanges = listOf(updateLocalChange),
        generatedPatch = updateLocalChange.toPatch(),
      )
    val generator = UrlRequestGenerator.getDefault()
    val requests =
      generator.generateUploadRequests(
        listOf(StronglyConnectedPatchMappings(listOf(patchOutput))),
      )
    with(requests.single()) {
      with(generatedRequest) {
        requests.shouldHaveSize(1)
        httpVerb.shouldBe(Bundle.HTTPVerb.Patch)
        url.shouldBe("Patient/Patient-001")
        (resource as Binary)
          .data
          ?.value
          ?.decodeBase64String()
          .shouldBe(
            "[{\"op\":\"replace\",\"path\":\"\\/name\\/0\\/given\\/0\",\"value\":\"Janet\"}]"
          )
      }
      localChanges.shouldBeEqual(patchOutput.localChanges)
    }
  }

  @Test
  fun should_create_a_delete_request_for_delete() = runTest {
    val patchOutput =
      PatchMapping(
        localChanges = listOf(deleteLocalChange),
        generatedPatch = deleteLocalChange.toPatch(),
      )
    val generator = UrlRequestGenerator.getDefault()
    val requests =
      generator.generateUploadRequests(
        listOf(StronglyConnectedPatchMappings(listOf(patchOutput))),
      )
    with(requests.single()) {
      with(generatedRequest) {
        httpVerb.shouldBeEqual(Bundle.HTTPVerb.Delete)
        url.shouldBeEqual("Patient/Patient-001")
      }
      localChanges.shouldBeEqual(patchOutput.localChanges)
    }
  }

  @Test
  fun should_return_multiple_requests_in_order() = runTest {
    val patchOutputList =
      listOf(insertionLocalChange, updateLocalChange, deleteLocalChange).map {
        PatchMapping(listOf(it), it.toPatch())
      }
    val generator = UrlRequestGenerator.getDefault()
    val result =
      generator.generateUploadRequests(
        patchOutputList.map { StronglyConnectedPatchMappings(listOf(it)) },
      )
    result.shouldHaveSize(3)
    result
      .map { it.generatedRequest.httpVerb }
      .shouldContainExactly(Bundle.HTTPVerb.Put, Bundle.HTTPVerb.Patch, Bundle.HTTPVerb.Delete)
  }
}
