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

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.ohs.fhir.engine.db.impl.deserializeResource
import dev.ohs.fhir.engine.db.impl.serializeResource
import dev.ohs.fhir.model.r4.Resource
import java.io.File
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * What the engine would gain, or lose, by storing `serializedResource` as a binary blob rather than
 * as JSON text.
 *
 * `ResourceEntity.serializedResource` is a `String` holding FHIR JSON. Every read pulls it out and
 * parses it, and a search materialises one per matching row, so its size drives how many pages a
 * read touches and how much of the page cache a corpus occupies. Encoding the same resources with
 * kotlinx ProtoBuf gives payloads roughly half the size — 431 to 217 bytes for a richly populated
 * patient, 202 to 84 for an observation carrying a `value[x]` — and round-trips exactly, including
 * through the polymorphic `Resource` serializer.
 *
 * Halving the bytes does not automatically halve anything that matters, which is the point of
 * measuring. Two effects pull against each other: fewer pages to read, and a different decoder.
 * [ResourceSerializerBenchmark] already puts JSON decode at a few microseconds per resource, so if
 * the binary form wins it should win on the read paths that touch many rows rather than on one.
 *
 * Deliberately raw SQL over a table of this benchmark's own making, not the engine's DAO. Room
 * fixes a column's type at compile time, so owning the schema is the only way to hold TEXT and BLOB
 * side by side in one run. That means this measures storage and parsing, not the DAO's own overhead
 * — an insert here is one row, where `ResourceDao` also re-indexes the resource across nine index
 * tables.
 */
@OptIn(ExperimentalSerializationApi::class)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class PayloadRepresentationBenchmark {

  /** `json` is what the engine stores today, as TEXT; `protobuf` is the same resource as a BLOB. */
  @Param("json", "protobuf") var representation: String = ""

  @Param("20000") var rows: Int = 0

  private lateinit var store: PayloadStore

  @Setup
  fun setUp() {
    store = PayloadStore(representation, tempFile("payload-$representation-$rows"))
    store.create()
    store.fill(rows, PAYLOAD_RESOURCES)
    store.prepareDecodeFixtures(PAYLOAD_RESOURCES)
    // Halving the payload is the entire premise; if the encoding ever stopped shrinking it, every
    // number here would still look plausible while measuring something else.
    val bytes = store.payloadBytes()
    println("[$representation] payload table holds $bytes bytes for $rows rows")
    check(bytes > 0) { "$representation stored no payload bytes" }
  }

  @TearDown fun tearDown() = store.close()

  /**
   * One resource by primary key: the `get` path, where payload size matters least.
   *
   * By id rather than `LIMIT 1 OFFSET n`, which would make SQLite walk every skipped row and turn a
   * point read into a scan of half the table.
   */
  @Benchmark
  fun readOneById(blackhole: Blackhole) {
    blackhole.consume(store.readOneAndDecode(id = rows / 2L))
  }

  /**
   * Many resources in one query: what a search does once it has its uuids, and the path where a
   * smaller payload has the most room to help.
   */
  @Benchmark
  fun readManyAndDecode(blackhole: Blackhole) {
    blackhole.consume(store.readAndDecode(limit = SEARCH_RESULTS, offset = 0))
  }

  /** The same rows fetched but not decoded, which separates the I/O from the parser. */
  @Benchmark
  fun readManyWithoutDecoding(blackhole: Blackhole) {
    blackhole.consume(store.readRaw(limit = SEARCH_RESULTS, offset = 0))
  }

  /**
   * Decoding alone, with no database involved.
   *
   * The end-to-end read is the sum of fetching bytes and parsing them, and the two move
   * independently: a binary payload is unambiguously fewer bytes, but kotlinx ProtoBuf is not
   * automatically a faster parser than kotlinx JSON. Measuring the parser on its own is the only
   * way to say which half dominates, and subtracting one noisy benchmark from another is not.
   */
  @Benchmark
  fun decodeOnly(blackhole: Blackhole) {
    blackhole.consume(store.decodePreEncoded(DECODE_COUNT))
  }

  /** Encoding plus the insert, which is what a write pays. */
  @Benchmark
  fun encodeAndInsert() {
    store.insert(INSERT_BATCH, PAYLOAD_RESOURCES)
  }

  private companion object {
    /** A realistic mix rather than one shape, since size is the variable under test. */
    val PAYLOAD_RESOURCES: List<Resource> =
      listOf(Fixtures.richPatient, Fixtures.observation, Fixtures.minimalPatient)

    /** A plausible page of search results. */
    const val SEARCH_RESULTS = 200
    const val INSERT_BATCH = 100
    const val DECODE_COUNT = 200

    fun tempFile(label: String): File =
      File.createTempFile("bench-$label-", ".db").also { it.delete() }
  }
}

/**
 * Encodes, stores and reads resources in one representation, over a table it owns.
 *
 * Raw driver rather than Room: the point is to hold a TEXT column and a BLOB column side by side,
 * and Room fixes the column type at compile time.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class PayloadStore(private val representation: String, private val file: File) {

  private val connection: SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath)

  private val isJson = representation == "json"

  init {
    require(representation == "json" || representation == "protobuf") {
      "Unknown representation: $representation"
    }
  }

  private lateinit var preEncodedText: List<String>
  private lateinit var preEncodedBytes: List<ByteArray>

  /** Captures the encoded forms once, so [decodePreEncoded] times only the parser. */
  fun prepareDecodeFixtures(resources: List<Resource>) {
    preEncodedText = resources.map(::encodeText)
    preEncodedBytes = resources.map(::encodeBytes)
  }

  private fun encodeText(resource: Resource) = serializeResource(resource)

  private fun encodeBytes(resource: Resource) =
    ProtoBuf.encodeToByteArray(Resource.serializer(), resource)

  fun create() {
    connection.execSQL(
      "CREATE TABLE payload (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        "payload ${if (isJson) "TEXT" else "BLOB"} NOT NULL)",
    )
  }

  /** Pre-encoded, so filling the table is not itself a measurement of the encoder. */
  fun fill(rows: Int, resources: List<Resource>) {
    val text = if (isJson) resources.map(::encodeText) else emptyList()
    val bytes = if (isJson) emptyList() else resources.map(::encodeBytes)
    inTransaction {
      connection.prepare("INSERT INTO payload (payload) VALUES (?)").use { statement ->
        repeat(rows) { row ->
          val slot = row % resources.size
          if (isJson) statement.bindText(1, text[slot]) else statement.bindBlob(1, bytes[slot])
          statement.step()
          statement.reset()
        }
      }
    }
  }

  /** Encoding sits inside this on purpose: a write pays for the encoder as well as the insert. */
  fun insert(count: Int, resources: List<Resource>) {
    inTransaction {
      connection.prepare("INSERT INTO payload (payload) VALUES (?)").use { statement ->
        repeat(count) { row ->
          val resource = resources[row % resources.size]
          if (isJson) {
            statement.bindText(1, encodeText(resource))
          } else {
            statement.bindBlob(1, encodeBytes(resource))
          }
          statement.step()
          statement.reset()
        }
      }
    }
  }

  /** Bytes fetched without decoding, which separates the storage cost from the parser's. */
  fun readRaw(limit: Int, offset: Int): Int =
    select(limit, offset) { statement ->
      if (isJson) statement.getText(0).length else statement.getBlob(0).size
    }

  /** Parses [count] already-encoded payloads, so nothing but the decoder is timed. */
  fun decodePreEncoded(count: Int): Int {
    var total = 0
    repeat(count) { index ->
      val slot = index % preEncodedText.size
      val resource =
        if (isJson) {
          deserializeResource(preEncodedText[slot])
        } else {
          ProtoBuf.decodeFromByteArray(Resource.serializer(), preEncodedBytes[slot])
        }
      total += resource.id?.length ?: 0
    }
    return total
  }

  /** A single row by primary key, which is an index seek rather than a scan. */
  fun readOneAndDecode(id: Long): Int =
    connection.prepare("SELECT payload FROM payload WHERE id = ?").use { statement ->
      statement.bindLong(1, id)
      if (statement.step()) decodeRow(statement) else 0
    }

  fun readAndDecode(limit: Int, offset: Int): Int = select(limit, offset, ::decodeRow)

  private fun decodeRow(statement: SQLiteStatement): Int {
    val resource =
      if (isJson) {
        deserializeResource(statement.getText(0))
      } else {
        ProtoBuf.decodeFromByteArray(Resource.serializer(), statement.getBlob(0))
      }
    return resource.id?.length ?: 0
  }

  /**
   * Total stored size, so a run can prove the representation under test is the one in the table.
   */
  fun payloadBytes(): Long =
    connection.prepare("SELECT SUM(LENGTH(payload)) FROM payload").use { statement ->
      if (statement.step()) statement.getLong(0) else 0L
    }

  fun close() {
    connection.close()
    file.delete()
  }

  private inline fun select(limit: Int, offset: Int, perRow: (SQLiteStatement) -> Int): Int {
    connection.prepare("SELECT payload FROM payload LIMIT ? OFFSET ?").use { statement ->
      statement.bindLong(1, limit.toLong())
      statement.bindLong(2, offset.toLong())
      var total = 0
      while (statement.step()) total += perRow(statement)
      return total
    }
  }

  private inline fun inTransaction(body: () -> Unit) {
    connection.execSQL("BEGIN IMMEDIATE")
    try {
      body()
      connection.execSQL("COMMIT")
    } catch (e: Throwable) {
      connection.execSQL("ROLLBACK")
      throw e
    }
  }
}
