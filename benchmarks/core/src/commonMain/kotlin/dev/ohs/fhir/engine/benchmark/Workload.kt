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
package dev.ohs.fhir.engine.benchmark

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.benchmark.data.Dataset

/** How a workload's database is reset between iterations; the choice changes what is measured. */
enum class Isolation {
  /** Leave the database alone. For read-only workloads. */
  NONE,

  /** Delete every row, keeping the open connection and a warm page cache. */
  CLEAR_TABLES,

  /** Close, delete and reopen the database. */
  FRESH_DATABASE,

  /**
   * Close and reopen the database without deleting it, dropping SQLite's page cache but not the OS
   * file cache. Applied by the runner in place of [NONE] when a run asks for a cold cache.
   */
  COLD_CACHE,
}

/** Everything a workload is given to do its work. */
class BenchmarkEnv(
  val engine: FhirEngine,
  val dataset: Dataset,
  val platformContext: Any,
  /** Closes the current engine and returns a new one on an empty database. */
  val reopenEngine: suspend () -> FhirEngine,
  /** Closes the current engine and reopens it over the existing data, dropping the page cache. */
  val reopenEngineKeepingData: suspend () -> FhirEngine = reopenEngine,
  /** Base URL of the FHIR server the engine was initialised against, or null. */
  val serverUrl: String? = null,
)

/** One measured unit of work. Only [run] is timed. */
interface Workload {
  /** Stable identifier, e.g. `search.observation_by_code`; also the trace-section name. */
  val id: String

  /** `crud`, `search`, `sync` or `server`. */
  val group: String

  /** Large enough to rise above Android's trace noise floor. */
  val opsPerIteration: Int

  val isolation: Isolation

  /** Runs once before any iteration. Untimed. */
  suspend fun prepare(env: BenchmarkEnv) {}

  /** Runs before every iteration, after isolation is applied. Untimed. */
  suspend fun beforeEach(env: BenchmarkEnv) {}

  /** The measured work. */
  suspend fun run(env: BenchmarkEnv)

  /** Runs after every iteration. Untimed. */
  suspend fun afterEach(env: BenchmarkEnv) {}
}

/**
 * The isolation a workload actually runs under, and a note when that is weaker than requested. Only
 * [Isolation.NONE] is promoted for a cold-cache run.
 *
 * @param canReopen whether this platform can close and reopen the database in-process.
 */
internal fun effectiveIsolation(
  requested: Isolation,
  coldCache: Boolean,
  canReopen: Boolean,
): Pair<Isolation, String?> =
  when {
    requested == Isolation.FRESH_DATABASE && !canReopen ->
      Isolation.CLEAR_TABLES to
        "Requested fresh_database but this platform cannot reopen the database in-process, so the " +
          "database was cleared instead and the page cache stayed warm. Treat as a lower bound."
    requested != Isolation.NONE || !coldCache -> requested to null
    canReopen -> Isolation.COLD_CACHE to null
    else ->
      Isolation.NONE to
        "Requested a cold cache but this platform cannot reopen the database in-process, so the " +
          "page cache stayed warm. This number is a warm read."
  }
