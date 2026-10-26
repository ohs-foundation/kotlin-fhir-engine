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
package dev.ohs.fhir.engine.microbenchmark

import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.LocalChangeToken
import dev.ohs.fhir.engine.db.LocalChangeResourceReference
import dev.ohs.fhir.engine.db.impl.serializeResource
import dev.ohs.fhir.engine.sync.upload.patch.PerChangePatchGenerator
import dev.ohs.fhir.engine.sync.upload.patch.PerResourcePatchGenerator
import dev.ohs.fhir.engine.sync.upload.patch.StronglyConnectedPatchMappings
import dev.ohs.fhir.engine.sync.upload.request.TransactionBundleGenerator
import dev.ohs.fhir.engine.sync.upload.request.UrlRequestGenerator
import kotlin.time.Instant
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Measures turning a queue of pending changes into upload requests. [changeCount] counts resources;
 * each has [CHANGES_PER_RESOURCE] changes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class UploadAssemblyBenchmark {

  @Param("50", "500") var changeCount: Int = 0

  private lateinit var localChanges: List<LocalChange>
  private lateinit var references: List<LocalChangeResourceReference>
  private lateinit var squashed: List<StronglyConnectedPatchMappings>

  private val bundleGenerator = TransactionBundleGenerator.getDefault()
  private val urlGenerator = UrlRequestGenerator.getDefault()

  @Setup
  fun setUp() {
    localChanges = buildLocalChanges(changeCount)
    references = buildReferences(changeCount)
    squashed = runBlocking { PerResourcePatchGenerator.generate(localChanges, references) }

    val patches = squashed.sumOf { it.patchMappings.size }
    check(patches == changeCount) {
      "squashing $changeCount resources produced $patches patches, so the changes are no longer " +
        "being merged per resource"
    }
    check(squashed.size < changeCount) {
      "no component collapsed, so the reference values no longer match any patch"
    }
  }

  /** The shipped default: one patch per resource, ordered by the references between them. */
  @Benchmark
  fun squashPerResource(blackhole: Blackhole) =
    blackhole.consume(runBlocking { PerResourcePatchGenerator.generate(localChanges, references) })

  /** Every change kept as its own patch, so no payload is replayed. */
  @Benchmark
  fun patchPerChange(blackhole: Blackhole) =
    blackhole.consume(runBlocking { PerChangePatchGenerator.generate(localChanges, references) })

  /** The squashed patches as transaction bundles. */
  @Benchmark
  fun bundleUploadRequests(blackhole: Blackhole) =
    blackhole.consume(bundleGenerator.generateUploadRequests(squashed))

  /** The squashed patches as one request each. */
  @Benchmark
  fun urlUploadRequests(blackhole: Blackhole) =
    blackhole.consume(urlGenerator.generateUploadRequests(squashed))

  /** An insert with the whole resource, then updates with RFC 6902 patches, per resource. */
  private fun buildLocalChanges(resources: Int): List<LocalChange> =
    (0 until resources).flatMap { index ->
      val inserted =
        localChange(
          index = index,
          changeIndex = 0,
          type = LocalChange.Type.INSERT,
          payload = serializeResource(EngineBenchmarkDatabase.patient(index)),
        )
      val updates =
        (1..UPDATES_PER_RESOURCE).map { update ->
          localChange(
            index = index,
            changeIndex = update,
            type = LocalChange.Type.UPDATE,
            payload = """[{"op":"replace","path":"/active","value":${update % 2 == 0}}]""",
          )
        }
      listOf(inserted) + updates
    }

  private fun localChange(
    index: Int,
    changeIndex: Int,
    type: LocalChange.Type,
    payload: String,
  ) =
    LocalChange(
      resourceType = RESOURCE_TYPE,
      resourceId = EngineBenchmarkDatabase.patientId(index),
      timestamp = TIMESTAMP,
      type = type,
      payload = payload,
      token = LocalChangeToken(ids = listOf(changeId(index, changeIndex))),
    )

  /**
   * Disjoint three-node cycles, which the ordering must collapse. Attached to each insert, because
   * only an insert forms an edge.
   */
  private fun buildReferences(resources: Int): List<LocalChangeResourceReference> =
    (0 until resources).mapNotNull { index ->
      val positionInTriple = index % CYCLE_LENGTH
      val target = index - positionInTriple + (positionInTriple + 1) % CYCLE_LENGTH
      if (target !in 0 until resources) {
        null
      } else {
        LocalChangeResourceReference(
          localChangeId = changeId(index, 0),
          resourceReferenceValue = "$RESOURCE_TYPE/${EngineBenchmarkDatabase.patientId(target)}",
          resourceReferencePath = "link",
        )
      }
    }

  private fun changeId(index: Int, changeIndex: Int): Long =
    (index.toLong() * CHANGES_PER_RESOURCE) + changeIndex

  private companion object {
    const val RESOURCE_TYPE = "Patient"
    const val UPDATES_PER_RESOURCE = 2
    const val CHANGES_PER_RESOURCE = UPDATES_PER_RESOURCE + 1
    const val CYCLE_LENGTH = 3

    val TIMESTAMP = Instant.fromEpochMilliseconds(1_766_000_000_000)
  }
}
