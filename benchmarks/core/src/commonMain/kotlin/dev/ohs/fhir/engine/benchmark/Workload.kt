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

/** Per workload, not uniform: the choice changes what is being measured. */
enum class Isolation {
  /** Leave the database alone. For read-only workloads that do not mutate anything. */
  NONE,

  /** Delete every row, keeping the open connection and a warm page cache. */
  CLEAR_TABLES,

  /** Close, delete and reopen the database. The only honest baseline for bulk writes. */
  FRESH_DATABASE,
}

/** Everything a workload is given to do its work. */
class BenchmarkEnv(
  val engine: FhirEngine,
  val dataset: Dataset,
  val platformContext: Any,
  /** Closes the current engine and returns a new one on an empty database. */
  val reopenEngine: suspend () -> FhirEngine,
  /** Base URL of the FHIR server the engine was initialised against, or null. */
  val serverUrl: String? = null,
)

/** One measured unit of work. Only [run] is timed. */
interface Workload {
  /**
   * Stable identifier, e.g. `search.observation_by_code`. Also the trace-section name, so one id
   * keys the Perfetto trace and the JSON report. Ids shared with android-fhir match theirs.
   */
  val id: String

  /** `crud`, `search`, `sync` or `server`. */
  val group: String

  /** Kept large: Android's trace noise floor is tens of microseconds. */
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
