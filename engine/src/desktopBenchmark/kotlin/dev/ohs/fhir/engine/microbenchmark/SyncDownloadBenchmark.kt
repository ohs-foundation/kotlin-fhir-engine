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

import dev.ohs.fhir.engine.impl.FhirEngineImpl
import dev.ohs.fhir.engine.sync.AcceptRemoteConflictResolver
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Level

/**
 * Measures `syncDownload` of [PAGES] x [PAGE] patients with [pendingChanges] unrelated local
 * changes queued.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class SyncDownloadBenchmark {

  @Param("0", "100", "1000") var pendingChanges: Int = 0

  /** `insert` downloads patients the database does not hold; `update` downloads them again. */
  @Param("insert", "update") var mode: String = ""

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var engine: FhirEngineImpl
  private lateinit var pages: List<List<Resource>>
  private lateinit var patientIds: Set<String>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("syncdownload-$pendingChanges")
    engine = FhirEngineImpl(database.database)
    pages =
      (0 until PAGES).map { page ->
        (0 until PAGE).map { EngineBenchmarkDatabase.patient(page * PAGE + it) }
      }
    patientIds = pages.flatten().mapNotNull { it.id }.toSet()

    val queued = (0 until MAX_PENDING).map { EngineBenchmarkDatabase.observation(it, MAX_PENDING) }
    database.insert(queued)
    database.discardChanges(queued.drop(pendingChanges))
    check(database.localChangeCount() == pendingChanges) {
      "the queue holds ${database.localChangeCount()} changes, expected $pendingChanges"
    }

    download()
    check(database.countOf(ResourceType.Patient) == (PAGES * PAGE).toLong()) {
      "the download wrote ${database.countOf(ResourceType.Patient)} patients, expected " +
        "${PAGES * PAGE}"
    }
    check(database.localChangeCount() == pendingChanges) {
      "the download consumed ${pendingChanges - database.localChangeCount()} queued changes, so " +
        "the arms no longer differ only in queue size"
    }
  }

  @TearDown fun tearDown() = database.close()

  /** Untimed. Purging records no local change, so the queue is untouched. */
  @Setup(Level.Invocation)
  fun clearPatients() {
    when (mode) {
      "insert" -> runBlocking { database.database.purge(ResourceType.Patient, patientIds) }
      "update" -> Unit
      else -> error("Unknown mode: $mode")
    }
  }

  @Benchmark
  fun download() = runBlocking {
    engine.syncDownload(AcceptRemoteConflictResolver) { flowOf(*pages.toTypedArray()) }
  }

  private companion object {
    const val PAGE = 100

    /** Enough pages that a per-page cost shows. */
    const val PAGES = 10

    /** Seeded by every arm, so only the queue size varies. */
    const val MAX_PENDING = 1_000
  }
}
