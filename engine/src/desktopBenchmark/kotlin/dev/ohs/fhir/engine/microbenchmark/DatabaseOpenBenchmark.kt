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

import java.io.File
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
import org.openjdk.jmh.annotations.Level

/** Opening the database, which Room does lazily on the first query. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class DatabaseOpenBenchmark {

  private lateinit var seededDirectory: File
  private lateinit var emptyDirectory: File
  private var opened: EngineBenchmarkDatabase? = null

  @Setup
  fun setUp() {
    val seeded = EngineBenchmarkDatabase.create("startup-seeded")
    seeded.seedPatients(SEEDED_ROWS)
    seededDirectory = seeded.directory
    seeded.database.close()
    check(seededDirectory.listFiles().orEmpty().isNotEmpty()) {
      "the seeded directory holds no database file, so openSeeded would create one instead"
    }
  }

  @TearDown
  fun tearDown() {
    seededDirectory.deleteRecursively()
  }

  /** A fresh directory per invocation, created untimed so only the open itself is measured. */
  @Setup(Level.Invocation)
  fun freshDirectory() {
    emptyDirectory = File.createTempFile("bench-startup-empty-", "")
    emptyDirectory.delete()
    emptyDirectory.mkdirs()
  }

  @TearDown(Level.Invocation)
  fun closeOpened() {
    opened?.database?.close()
    opened = null
    emptyDirectory.deleteRecursively()
  }

  /** First launch after install: the schema is created before the query can be answered. */
  @Benchmark
  fun openEmptyDatabase(blackhole: Blackhole) {
    val database = EngineBenchmarkDatabase(emptyDirectory)
    opened = database
    blackhole.consume(database.localChangeCount())
  }

  /** Every launch afterwards, against a database that already holds a corpus. */
  @Benchmark
  fun openSeededDatabase(blackhole: Blackhole) {
    val database = EngineBenchmarkDatabase(seededDirectory)
    opened = database
    blackhole.consume(database.localChangeCount())
  }

  private companion object {
    /** Enough rows that the file is not empty; the open does not read them. */
    const val SEEDED_ROWS = 500
  }
}
