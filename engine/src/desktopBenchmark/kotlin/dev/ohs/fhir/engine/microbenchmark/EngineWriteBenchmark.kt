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

import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
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
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Level

/*
 * The write path through `DatabaseImpl`. Each invocation writes a batch, not one resource, because
 * JMH's per-invocation hooks are unreliable below a millisecond.
 */

/** Creating resources locally, which records an insert per resource in the ledger. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineCreateBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("create")
    batch = writeBatch()
  }

  @TearDown fun tearDown() = database.close()

  /** Removes both the rows and the ledger entries so neither table grows across invocations. */
  @TearDown(Level.Invocation)
  fun removeWritten() {
    database.delete(batch)
    database.discardChanges(batch)
  }

  @Benchmark fun createBatch(): List<String> = database.insert(batch)
}

/**
 * Updating resources that already exist, which diffs each against its stored payload. Two variants
 * alternate because `LocalChangeDao.addUpdate` skips an update that matches the stored payload.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineUpdateBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>
  private lateinit var variants: List<List<Patient>>
  private var invocation = 0

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("update")
    batch = writeBatch()
    variants =
      listOf(
        batch.map { it.copy(active = FhirBoolean(value = false)) },
        batch.map { it.copy(active = FhirBoolean(value = true)) },
      )
    // Seeded as remote so the ledger starts empty.
    database.seedGiven(batch)
    check(database.localChangeCount() == 0) {
      "seeding recorded ${database.localChangeCount()} local changes; the update arm would then " +
        "be measuring a merge into them"
    }
    variants.forEachIndexed { index, variant ->
      database.update(variant)
      check(database.localChangeCount() == CrudFixture.BATCH) {
        "variant $index recorded ${database.localChangeCount()} changes rather than " +
          "${CrudFixture.BATCH}; an update that matches the stored payload is skipped, so this " +
          "arm would be timing a diff that finds nothing"
      }
      database.discardChanges(batch)
    }
  }

  @TearDown fun tearDown() = database.close()

  @TearDown(Level.Invocation)
  fun discardRecorded() {
    database.discardChanges(batch)
  }

  @Benchmark fun updateBatch() = database.update(variants[invocation++ % variants.size])
}

/** Deleting, which cascades across nine index tables and records a delete per resource. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class EngineDeleteBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("delete")
    batch = writeBatch()
    // A delete that matches nothing returns early, so check once that the cycle moves rows.
    database.seedGiven(batch)
    check(database.countOf(ResourceType.Patient) == CrudFixture.BATCH.toLong()) {
      "seeding left ${database.countOf(ResourceType.Patient)} patients, expected " +
        "${CrudFixture.BATCH}"
    }
    database.delete(batch)
    check(database.countOf(ResourceType.Patient) == 0L) {
      "deleting the batch left ${database.countOf(ResourceType.Patient)} patients behind, so " +
        "deleteBatch would be timing misses"
    }
    database.discardChanges(batch)
  }

  @TearDown fun tearDown() = database.close()

  /** Restores the deleted rows and clears the ledger, untimed. */
  @Setup(Level.Invocation)
  fun restoreRows() {
    database.discardChanges(batch)
    database.seedGiven(batch)
  }

  @Benchmark fun deleteBatch() = database.delete(batch)
}

/**
 * The download path: many resources in one transaction, with no local change recorded. The batch is
 * larger than the other classes', so compare per resource.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class BulkImportBenchmark {

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("import")
    batch = (0 until BULK).map(EngineBenchmarkDatabase::patient)
  }

  @TearDown fun tearDown() = database.close()

  /** Every invocation imports the same corpus into an empty database. */
  @TearDown(Level.Invocation)
  fun emptyDatabase() {
    database.clear()
  }

  @Benchmark fun importBatch() = database.importRemote(batch)

  private companion object {
    const val BULK = 500
  }
}

/**
 * Reading the pending local changes and their references, which upload does before it builds a
 * request. [UploadAssemblyBenchmark] measures what happens to them afterwards.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class LocalChangeReadBenchmark {

  @Param("50", "500") var changeCount: Int = 0

  private lateinit var database: EngineBenchmarkDatabase
  private lateinit var changeIds: List<Long>

  @Setup
  fun setUp() {
    database = EngineBenchmarkDatabase.create("localchange-$changeCount")
    // Created locally with a reference, so each has a change row and a reference row.
    database.insert(
      (0 until changeCount).map { EngineBenchmarkDatabase.observation(it, changeCount) },
    )
    check(database.localChangeCount() == changeCount) {
      "expected $changeCount pending changes, found ${database.localChangeCount()}"
    }
    changeIds = database.allLocalChanges().flatMap { it.token.ids }
    check(database.localChangeReferences(changeIds).isNotEmpty()) {
      "no reference rows were recorded, so readReferences would measure an empty lookup"
    }
  }

  @TearDown fun tearDown() = database.close()

  @Benchmark
  fun readAllLocalChanges(blackhole: Blackhole) {
    blackhole.consume(database.allLocalChanges())
  }

  @Benchmark
  fun readReferencesForChanges(blackhole: Blackhole) {
    blackhole.consume(database.localChangeReferences(changeIds))
  }
}

private fun writeBatch(): List<Patient> =
  (0 until CrudFixture.BATCH).map(EngineBenchmarkDatabase::patient)
