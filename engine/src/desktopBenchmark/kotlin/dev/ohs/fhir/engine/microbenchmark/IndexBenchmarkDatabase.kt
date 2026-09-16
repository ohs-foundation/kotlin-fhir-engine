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

import androidx.room3.PooledConnection
import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.engine.db.impl.ResourceDatabase
import dev.ohs.fhir.engine.search.SearchQuery
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A real [ResourceDatabase] populated straight through its index tables, for asking what SQLite
 * does with a given index at a given size.
 *
 * Rows are inserted as raw SQL rather than through `FhirEngine`, because the question is what the
 * index costs, not what indexing costs. That is also what makes 50,000 rows affordable: the engine
 * path would spend a FHIRPath evaluation per resource — about 200 us each, measured by
 * [ResourceIndexerBenchmark] — and turn a few seconds of setup into twenty minutes of it.
 *
 * File-backed on purpose. An in-memory database ignores `journal_mode`, so any PRAGMA comparison
 * run against one would report no difference for the wrong reason.
 */
internal class IndexBenchmarkDatabase(private val file: File) {

  private val database =
    Room.databaseBuilder<ResourceDatabase>(file.absolutePath)
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()

  /**
   * Writes [rows] patients, each with one date index row and one string index row.
   *
   * Both value distributions are scale-invariant: dates spread evenly across [DAY_SPREAD] and
   * string prefixes cycle through all 676 two-letter combinations, so a fixed query selects the
   * same fraction of rows at every size. That is what makes a scaling curve mean something.
   */
  fun seed(rows: Int) = runBlocking {
    database.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        usePrepared(
          "INSERT INTO ResourceEntity (resourceUuid, resourceType, resourceId, serializedResource) " +
            "VALUES (?, 'Patient', ?, '{}')",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "patient-$row")
            statement.step()
            statement.reset()
          }
        }
        usePrepared(
          "INSERT INTO DateIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_from, index_to) " +
            "VALUES (?, 'Patient', 'birthdate', 'Patient.birthDate', ?, ?)",
        ) { statement ->
          repeat(rows) { row ->
            // Spread across the full range whatever the row count, so a fixed date window selects
            // the same *fraction* at every scale. Tying it to `row` alone would make the window
            // select everything at 1,000 rows and a sliver at 50,000, and the scaling curve would
            // be
            // measuring changing selectivity rather than changing size.
            val day = row.toLong() * DAY_SPREAD / rows
            statement.bindText(1, uuidFor(row))
            statement.bindLong(2, day)
            statement.bindLong(3, day)
            statement.step()
            statement.reset()
          }
        }
        usePrepared(
          "INSERT INTO StringIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_value) " +
            "VALUES (?, 'Patient', 'given', 'Patient.name.given', ?)",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            // A two-letter prefix selects roughly 1/676 of the rows, so a prefix search is
            // selective
            // enough for an index to have something to win.
            statement.bindText(2, "${prefixFor(row)}name-$row")
            statement.step()
            statement.reset()
          }
        }
      }
    }
  }

  /**
   * Appends [count] string index rows, reusing existing patient uuids. A write that has to maintain
   * an index, which is what journal and synchronous settings act on.
   */
  fun insertIndexRows(count: Int, batch: Int) = runBlocking {
    database.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        usePrepared(
          "INSERT INTO StringIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_value) " +
            "VALUES (?, 'Patient', 'family', '$INSERTED_MARKER', ?)",
        ) { statement ->
          repeat(count) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "batch$batch-row$row")
            statement.step()
            statement.reset()
          }
        }
      }
    }
  }

  /**
   * Appends [count] string index rows, each in its own transaction.
   *
   * The counterpart to [insertIndexRows], and the shape that actually exercises journal mode.
   * `journal_mode` and `synchronous` govern what a *commit* must durably record, so one transaction
   * of five hundred rows amortises them almost to nothing while five hundred transactions of one
   * row pays them five hundred times over. An engine saving a resource at a time is the second
   * shape, so measuring only the first answers the easier question.
   */
  fun insertIndexRowsPerTransaction(count: Int, batch: Int) = runBlocking {
    database.useWriterConnection { transactor ->
      repeat(count) { row ->
        transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
          usePrepared(
            "INSERT INTO StringIndexEntity " +
              "(resourceUuid, resourceType, index_name, index_path, index_value) " +
              "VALUES (?, 'Patient', 'family', '$INSERTED_MARKER', ?)",
          ) { statement ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "single$batch-row$row")
            statement.step()
          }
        }
      }
    }
  }

  /**
   * Removes everything the write benchmarks appended, returning the table to its seeded size.
   *
   * Without this each invocation leaves its rows behind, so the index grows all through an
   * iteration and later invocations measure a bigger tree than earlier ones. That shows up as a
   * drifting mean and an error bar wider than the number it qualifies.
   */
  fun deleteInsertedRows() = runBlocking {
    database.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        usePrepared(
          "DELETE FROM StringIndexEntity WHERE index_name = 'family' AND index_path = ?",
        ) { statement ->
          statement.bindText(1, INSERTED_MARKER)
          statement.step()
        }
      }
    }
  }

  /** Replaces the indices on [table] with [definitions]. Each is the body of a CREATE INDEX. */
  fun reindex(table: String, definitions: List<String>) = runBlocking {
    database.useWriterConnection { transactor ->
      transactor
        .usePrepared(
          "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND name LIKE 'index_%'",
        ) { statement ->
          statement.bindText(1, table)
          val existing = mutableListOf<String>()
          while (statement.step()) existing += statement.getText(0)
          existing
        }
        .forEach { transactor.exec("DROP INDEX IF EXISTS `$it`") }
      definitions.forEachIndexed { index, columns ->
        transactor.exec("CREATE INDEX `bench_${table}_$index` ON `$table` ($columns)")
      }
    }
  }

  /** Populates `sqlite_stat1`, which is empty until something asks for it. */
  fun analyze() = runBlocking { database.useWriterConnection { it.exec("ANALYZE") } }

  fun pragma(statement: String) = runBlocking {
    database.useWriterConnection { it.exec("PRAGMA $statement") }
  }

  /** Runs [query] and returns the row count, so the result cannot be optimised away. */
  fun count(query: SearchQuery): Int = runBlocking {
    database.useReaderConnection { transactor ->
      transactor.usePrepared(query.query) { statement ->
        bindArgs(statement, query.args)
        var rows = 0
        while (statement.step()) rows++
        rows
      }
    }
  }

  /**
   * The plan SQLite chose, for asserting in `@Setup` that the shape under test is really in use.
   */
  fun planFor(query: SearchQuery): List<String> = runBlocking {
    database.useReaderConnection { transactor ->
      transactor.usePrepared("EXPLAIN QUERY PLAN ${query.query}") { statement ->
        bindArgs(statement, query.args)
        val steps = mutableListOf<String>()
        while (statement.step()) steps += statement.getText(3)
        steps
      }
    }
  }

  fun close() {
    database.close()
    file.delete()
    File("${file.absolutePath}-wal").delete()
    File("${file.absolutePath}-shm").delete()
  }

  /** [PooledConnection] only exposes prepared statements, so DDL and PRAGMAs go through one too. */
  private suspend fun PooledConnection.exec(sql: String) {
    usePrepared(sql) { it.step() }
  }

  private fun bindArgs(statement: SQLiteStatement, args: List<Any>) {
    args.forEachIndexed { i, arg ->
      when (arg) {
        is String -> statement.bindText(i + 1, arg)
        is Long -> statement.bindLong(i + 1, arg)
        is Double -> statement.bindDouble(i + 1, arg)
        is Int -> statement.bindLong(i + 1, arg.toLong())
        else -> statement.bindText(i + 1, arg.toString())
      }
    }
  }

  companion object {
    /** Tags rows a write benchmark added, so [deleteInsertedRows] can remove exactly those. */
    const val INSERTED_MARKER = "Benchmark.inserted"

    /** Roughly 55 years of birthdates, so a decade-wide range is a real slice. */
    const val DAY_SPREAD = 20_000

    private val LETTERS = ('a'..'z').toList()

    fun prefixFor(row: Int): String {
      val first = LETTERS[(row / LETTERS.size) % LETTERS.size]
      val second = LETTERS[row % LETTERS.size]
      return "$first$second"
    }

    fun uuidFor(row: Int) = "00000000-0000-0000-0000-${row.toString().padStart(12, '0')}"

    /** A fresh database file per trial, so nothing carries over between parameter combinations. */
    fun create(label: String): IndexBenchmarkDatabase {
      val file = File.createTempFile("bench-$label-", ".db")
      file.delete()
      return IndexBenchmarkDatabase(file)
    }
  }
}
