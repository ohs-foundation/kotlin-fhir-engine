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
import dev.ohs.fhir.engine.sync.upload.patch.Patch
import dev.ohs.fhir.engine.sync.upload.patch.PatchMapping
import dev.ohs.fhir.engine.sync.upload.patch.PatchOrdering.sccOrderByReferences
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

/**
 * Ordering pending uploads runs Tarjan's algorithm over the graph of references between them. It is
 * the one place in the engine whose cost grows with the size of the offline queue rather than with
 * a single resource, so a complexity regression here would stay invisible until a device that had
 * been offline for a long time tried to sync.
 *
 * [changeCount] is swept so the shape of that growth is visible, not just a single number.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class PatchOrderingBenchmark {

  @Param("50", "500") var changeCount: Int = 0

  private lateinit var chainedMappings: List<PatchMapping>
  private lateinit var chainedReferences: List<LocalChangeResourceReference>
  private lateinit var cyclicMappings: List<PatchMapping>
  private lateinit var cyclicReferences: List<LocalChangeResourceReference>

  @Setup
  fun setUp() {
    // A chain: each patch references the one before it, so every component is a single node and
    // the algorithm does the least work it can while still traversing everything.
    chainedMappings = buildMappings(changeCount)
    chainedReferences = buildReferences(changeCount) { index -> listOf(index - 1) }

    // Disjoint three-node cycles, which force Tarjan's to collapse components rather than emit
    // one node each. Cycles are the case the engine has to handle correctly, so they are the case
    // worth timing against the acyclic baseline.
    cyclicMappings = buildMappings(changeCount)
    cyclicReferences =
      buildReferences(changeCount) { index ->
        val positionInTriple = index % CYCLE_LENGTH
        val startOfTriple = index - positionInTriple
        listOf(startOfTriple + (positionInTriple + 1) % CYCLE_LENGTH)
      }
  }

  // StronglyConnectedPatchMappings is internal to the engine, so a public @Benchmark method
  // cannot return it. Blackhole consumption keeps the result from being optimised away instead.
  @Benchmark
  fun orderChainedReferences(blackhole: Blackhole) =
    blackhole.consume(chainedMappings.sccOrderByReferences(chainedReferences))

  @Benchmark
  fun orderCyclicReferences(blackhole: Blackhole) =
    blackhole.consume(cyclicMappings.sccOrderByReferences(cyclicReferences))

  /** One INSERT patch per pending change, since only INSERTs form edges in the graph. */
  private fun buildMappings(count: Int): List<PatchMapping> =
    (0 until count).map { index ->
      val localChange =
        LocalChange(
          resourceType = RESOURCE_TYPE,
          resourceId = resourceId(index),
          timestamp = TIMESTAMP,
          type = LocalChange.Type.INSERT,
          payload = "{}",
          token = LocalChangeToken(ids = listOf(index.toLong())),
        )
      PatchMapping(
        localChanges = listOf(localChange),
        generatedPatch =
          Patch(
            resourceType = RESOURCE_TYPE,
            resourceId = resourceId(index),
            timestamp = TIMESTAMP,
            type = Patch.Type.INSERT,
            payload = "{}",
          ),
      )
    }

  /**
   * References are keyed by local-change id, which [buildMappings] sets equal to the change's own
   * index. [targets] names the indices a change points at; targets outside the range are dropped,
   * which is what lets a chain's first element simply have no outgoing edge.
   */
  private fun buildReferences(
    count: Int,
    targets: (Int) -> List<Int>,
  ): List<LocalChangeResourceReference> =
    (0 until count).flatMap { index ->
      targets(index)
        .filter { it in 0 until count }
        .map { target ->
          LocalChangeResourceReference(
            localChangeId = index.toLong(),
            resourceReferenceValue = nodeId(target),
            resourceReferencePath = "subject",
          )
        }
    }

  private fun resourceId(index: Int) = "resource-$index"

  /**
   * How `PatchOrdering` names a node: the patch's type and id joined, which is also the form a
   * reference value takes. The two must agree or every edge is silently dropped.
   */
  private fun nodeId(index: Int) = "$RESOURCE_TYPE/${resourceId(index)}"

  private companion object {
    const val RESOURCE_TYPE = "Patient"
    const val CYCLE_LENGTH = 3
    val TIMESTAMP = Instant.fromEpochMilliseconds(1_766_000_000_000)
  }
}
