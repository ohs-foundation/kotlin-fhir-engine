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

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.engine.db.impl.ResourceDatabase
import dev.ohs.fhir.engine.db.impl.dao.ResourceDao
import dev.ohs.fhir.engine.db.impl.entities.ResourceEntity
import dev.ohs.fhir.engine.db.impl.serializeResource
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Level

/**
 * The engine's CRUD paths, through the real DAO and the real schema.
 *
 * Nothing else here touches `ResourceDao`: the other benchmarks measure pure-CPU code or hand-built
 * tables, so a regression in an insert, an update or the delete cascade would not show up anywhere.
 *
 * Each operation gets its own class rather than sharing one, because they need opposite fixtures. A
 * JMH per-invocation hook belongs to the whole `@State`, so an insert benchmark and a delete
 * benchmark in one class would run each other's setup and quietly measure the pair.
 *
 * Every invocation does a batch rather than a single operation. JMH's per-invocation hooks are
 * unreliable for work measured in microseconds, and a batch of [CrudFixture.BATCH] lifts each
 * invocation into the tens of milliseconds where they are sound.
 */
internal class CrudFixture(label: String) {

  private val file: File = File.createTempFile("bench-crud-$label-", ".db").also { it.delete() }

  private val database =
    Room.databaseBuilder<ResourceDatabase>(file.absolutePath)
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()

  private val dao: ResourceDao =
    database.resourceDao().also {
      it.resourceIndexer = ResourceIndexer(SearchParamDefinitionsProviderImpl())
    }

  /** Distinct ids so a batch never collides with another benchmark's rows. */
  fun patients(offset: Int, count: Int = BATCH): List<Patient> =
    (offset until offset + count).map { Fixtures.richPatient.copy(id = "crud-$it") }

  /** A changed copy, so an update is a real write rather than a no-op. */
  fun modified(patients: List<Patient>): List<Patient> =
    patients.map { it.copy(active = FhirBoolean(value = false)) }

  /** The shipped write path: index the resource, then write the row and its index rows. */
  fun insertIndexed(patients: List<Patient>) = runBlocking {
    patients.forEach { dao.insertLocalResource(it, TIMESTAMP) }
  }

  /**
   * The row without its indices, which is the same SQL minus the FHIRPath.
   *
   * `ResourceIndexerBenchmark` puts indexing at 200-360 us a resource, so an insert measured with
   * it is mostly a measurement of the path evaluator. The gap between this and [insertIndexed] is
   * what the storage layer itself costs — measured, rather than inferred by subtracting one noisy
   * benchmark from another.
   */
  fun insertRowOnly(patients: List<Patient>) = runBlocking {
    patients.forEach { patient ->
      dao.insertResource(
        ResourceEntity(
          id = 0,
          resourceUuid = Uuid.random(),
          resourceType = ResourceType.Patient,
          resourceId = patient.id.orEmpty(),
          serializedResource = serializeResource(patient),
          versionId = null,
          lastUpdatedRemote = null,
          lastUpdatedLocal = TIMESTAMP,
        ),
      )
    }
  }

  /**
   * An update is not a row rewrite. `ResourceDao.updateChanges` re-encodes the resource and does a
   * REPLACE insert, which cascade-deletes every index row for it across nine tables and writes them
   * all again — so changing one field pays for full re-indexing.
   */
  fun update(patients: List<Patient>) = runBlocking {
    patients.forEach { dao.applyLocalUpdate(it, TIMESTAMP) }
  }

  /** Returns rows removed, so a delete that matched nothing cannot pass for a fast one. */
  fun delete(patients: List<Patient>): Int = runBlocking {
    patients.sumOf { dao.deleteResource(it.id.orEmpty(), ResourceType.Patient) }
  }

  fun read(patients: List<Patient>): Int = runBlocking {
    patients.count { dao.getResource(it.id.orEmpty(), ResourceType.Patient) != null }
  }

  fun close() {
    database.close()
    file.delete()
    File("${file.absolutePath}-wal").delete()
    File("${file.absolutePath}-shm").delete()
  }

  companion object {
    /** Large enough that a per-invocation hook is sound, small enough to stay quick. */
    const val BATCH = 50

    val TIMESTAMP: Instant = Instant.fromEpochMilliseconds(1_766_000_000_000)
  }
}

/** Writing new resources. Each invocation's rows are removed afterwards, untimed. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceInsertBenchmark {

  private lateinit var fixture: CrudFixture
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    fixture = CrudFixture("insert")
    batch = fixture.patients(offset = 0)
  }

  @TearDown fun tearDown() = fixture.close()

  /**
   * Without this the table grows for the whole run and every later invocation writes into a bigger
   * index than the one before it, which reads as a drifting mean rather than as a mistake.
   */
  @TearDown(Level.Invocation)
  fun removeInserted() {
    fixture.delete(batch)
  }

  @Benchmark fun insertIndexed() = fixture.insertIndexed(batch)

  @Benchmark fun insertRowOnly() = fixture.insertRowOnly(batch)
}

/** Updating resources that already exist. Idempotent, so the corpus never changes size. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceUpdateBenchmark {

  private lateinit var fixture: CrudFixture
  private lateinit var batch: List<Patient>
  private lateinit var changed: List<Patient>

  @Setup
  fun setUp() {
    fixture = CrudFixture("update")
    batch = fixture.patients(offset = 0)
    changed = fixture.modified(batch)
    fixture.insertIndexed(batch)
    check(fixture.read(batch) == CrudFixture.BATCH) {
      "update benchmark did not seed its rows, so it would measure a lookup that always misses"
    }
  }

  @TearDown fun tearDown() = fixture.close()

  @Benchmark fun updateExisting() = fixture.update(changed)
}

/** Deleting. Rows are re-created before each invocation, untimed, since a delete consumes them. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceDeleteBenchmark {

  private lateinit var fixture: CrudFixture
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    fixture = CrudFixture("delete")
    batch = fixture.patients(offset = 0)
  }

  @TearDown fun tearDown() = fixture.close()

  @Setup(Level.Invocation)
  fun restoreRows() {
    fixture.insertIndexed(batch)
  }

  /**
   * Deleting cascades to every index row for the resource across nine tables, which is most of what
   * this costs. The returned count is consumed so an empty delete cannot pass for a fast one.
   */
  @Benchmark
  fun deleteExisting(blackhole: Blackhole) {
    val removed = fixture.delete(batch)
    check(removed == CrudFixture.BATCH) { "deleted $removed rows, expected ${CrudFixture.BATCH}" }
    blackhole.consume(removed)
  }
}

/** Reading by id. Read-only, so it needs no reset at all. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceReadBenchmark {

  private lateinit var fixture: CrudFixture
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    fixture = CrudFixture("read")
    batch = fixture.patients(offset = 0)
    fixture.insertIndexed(batch)
    check(fixture.read(batch) == CrudFixture.BATCH) {
      "read benchmark found none of its rows, so it would be timing misses"
    }
  }

  @TearDown fun tearDown() = fixture.close()

  @Benchmark
  fun readById(blackhole: Blackhole) {
    blackhole.consume(fixture.read(batch))
  }
}
