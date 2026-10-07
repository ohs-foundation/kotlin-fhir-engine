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
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import java.io.File
import kotlin.time.Instant
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

/** A real [ResourceDatabase] and [ResourceDao], seeded with [BATCH] patients. */
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

  fun patients(count: Int = BATCH): List<Patient> =
    (0 until count).map { Fixtures.richPatient.copy(id = "crud-$it") }

  fun insertIndexed(patients: List<Patient>) = runBlocking {
    patients.forEach { dao.insertLocalResource(it, TIMESTAMP) }
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
    const val BATCH = 50

    val TIMESTAMP: Instant = Instant.fromEpochMilliseconds(1_766_000_000_000)
  }
}

/** Reading [CrudFixture.BATCH] patients by id. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class ResourceReadBenchmark {

  private lateinit var fixture: CrudFixture
  private lateinit var batch: List<Patient>

  @Setup
  fun setUp() {
    fixture = CrudFixture("read")
    batch = fixture.patients()
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
