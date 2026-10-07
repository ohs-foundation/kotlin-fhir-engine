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
import androidx.sqlite.async.step
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.engine.db.impl.ResourceDatabase
import dev.ohs.fhir.engine.db.impl.bindArgs
import dev.ohs.fhir.engine.search.SearchQuery
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A real [ResourceDatabase] seeded with raw SQL straight into its index tables, without
 * `FhirEngine`'s FHIRPath indexing. File-backed, because an in-memory database ignores
 * `journal_mode`.
 */
internal class IndexBenchmarkDatabase(private val file: File) {

  private val database =
    Room.databaseBuilder<ResourceDatabase>(file.absolutePath)
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()

  /**
   * Writes [rows] patients, each with one date index row and one string index row. Values are
   * spread so a fixed query selects the same fraction of rows at every size.
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
            statement.bindText(2, "${prefixFor(row)}name-$row")
            statement.step()
            statement.reset()
          }
        }
      }
    }
  }

  /**
   * Writes [rows] observations, each with one quantity index row. Codes cycle through
   * [QUANTITY_UNITS] and values spread evenly across [VALUE_SPREAD].
   */
  fun seedQuantity(rows: Int) = runBlocking {
    database.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        usePrepared(
          "INSERT INTO ResourceEntity (resourceUuid, resourceType, resourceId, serializedResource) " +
            "VALUES (?, 'Observation', ?, '{}')",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "observation-$row")
            statement.step()
            statement.reset()
          }
        }
        usePrepared(
          "INSERT INTO QuantityIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_system, index_code, " +
            "index_value) " +
            "VALUES (?, 'Observation', 'value-quantity', 'Observation.value.ofType(Quantity)', " +
            "?, ?, ?)",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, UCUM_SYSTEM)
            statement.bindText(3, QUANTITY_UNITS[row % QUANTITY_UNITS.size])
            statement.bindDouble(4, row.toDouble() * VALUE_SPREAD / rows)
            statement.step()
            statement.reset()
          }
        }
      }
    }
  }

  /**
   * Writes [rows] observations, each with one reference index row and one uri index row. Values
   * cycle through [LOOKUP_VALUES].
   */
  fun seedLookups(rows: Int) = runBlocking {
    database.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        usePrepared(
          "INSERT INTO ResourceEntity (resourceUuid, resourceType, resourceId, serializedResource) " +
            "VALUES (?, 'Observation', ?, '{}')",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "observation-$row")
            statement.step()
            statement.reset()
          }
        }
        usePrepared(
          "INSERT INTO ReferenceIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_value) " +
            "VALUES (?, 'Observation', 'subject', 'Observation.subject', ?)",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "Patient/${LOOKUP_VALUES[row % LOOKUP_VALUES.size]}")
            statement.step()
            statement.reset()
          }
        }
        usePrepared(
          "INSERT INTO UriIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_value) " +
            "VALUES (?, 'Observation', 'identifier', 'Observation.identifier', ?)",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "urn:oid:${LOOKUP_VALUES[row % LOOKUP_VALUES.size]}")
            statement.step()
            statement.reset()
          }
        }
      }
    }
  }

  /**
   * Writes [rows] observations, each with one token index row in [TOKEN_SYSTEM]. Codes cycle
   * through [LOOKUP_VALUES].
   */
  fun seedTokens(rows: Int) = runBlocking {
    database.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        usePrepared(
          "INSERT INTO ResourceEntity (resourceUuid, resourceType, resourceId, serializedResource) " +
            "VALUES (?, 'Observation', ?, '{}')",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, "observation-$row")
            statement.step()
            statement.reset()
          }
        }
        usePrepared(
          "INSERT INTO TokenIndexEntity " +
            "(resourceUuid, resourceType, index_name, index_path, index_system, index_value) " +
            "VALUES (?, 'Observation', 'code', 'Observation.code', ?, ?)",
        ) { statement ->
          repeat(rows) { row ->
            statement.bindText(1, uuidFor(row))
            statement.bindText(2, TOKEN_SYSTEM)
            statement.bindText(3, LOOKUP_VALUES[row % LOOKUP_VALUES.size])
            statement.step()
            statement.reset()
          }
        }
      }
    }
  }

  /** Appends [count] string index rows in one transaction, reusing existing patient uuids. */
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

  /** Appends [count] string index rows, each in its own transaction. */
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

  /** Removes the rows tagged with [INSERTED_MARKER]. */
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

  /**
   * Replaces Room's generated indices (`index_%`) on [table] with [definitions], each the body of a
   * CREATE INDEX.
   */
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

  fun pragma(statement: String) = runBlocking {
    database.useWriterConnection { it.exec("PRAGMA $statement") }
  }

  /** Reads a PRAGMA back, so an arm can check its setting is in force. */
  fun pragmaValue(name: String): String = runBlocking {
    database.useReaderConnection { transactor ->
      transactor.usePrepared("PRAGMA $name") { statement ->
        if (statement.step()) statement.getText(0) else ""
      }
    }
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

  /** The plan SQLite chose for [query]. */
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

  companion object {
    /** Tags appended rows so [deleteInsertedRows] removes only those. */
    const val INSERTED_MARKER = "Benchmark.inserted"

    /** Days the seeded birthdates spread across. */
    const val DAY_SPREAD = 20_000

    const val UCUM_SYSTEM = "http://unitsofmeasure.org"

    const val TOKEN_SYSTEM = "http://loinc.org"

    /**
     * Units the engine's UCUM canonicalisation leaves unchanged, so seeded and queried codes match.
     */
    val QUANTITY_UNITS = listOf("g/dL", "mg/dL", "/min", "mmol/L", "U/L", "ng/mL", "pg/mL", "mIU/L")

    /** Quantity values spread across this range, whatever the row count. */
    const val VALUE_SPREAD = 10.0

    /** Values the reference, uri and token index rows cycle through. */
    val LOOKUP_VALUES = (0 until 64).map { "lookup-$it" }

    private val LETTERS = ('a'..'z').toList()

    /** How many distinct prefixes [prefixFor] cycles through. */
    val PREFIX_COMBINATIONS = LETTERS.size * LETTERS.size

    /** An arbitrary row, for a benchmark needing one value out of a seeded cycle. */
    const val PROBE_ROW = 42

    fun prefixFor(row: Int): String {
      val first = LETTERS[(row / LETTERS.size) % LETTERS.size]
      val second = LETTERS[row % LETTERS.size]
      return "$first$second"
    }

    fun uuidFor(row: Int) = "00000000-0000-0000-0000-${row.toString().padStart(12, '0')}"

    /** A fresh database file per trial. */
    fun create(label: String): IndexBenchmarkDatabase {
      val file = File.createTempFile("bench-$label-", ".db")
      file.delete()
      return IndexBenchmarkDatabase(file)
    }
  }
}
