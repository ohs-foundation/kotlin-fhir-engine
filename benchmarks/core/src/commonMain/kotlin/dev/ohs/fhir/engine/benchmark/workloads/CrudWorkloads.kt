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
package dev.ohs.fhir.engine.benchmark.workloads

import dev.ohs.fhir.engine.benchmark.BenchmarkEnv
import dev.ohs.fhir.engine.benchmark.Isolation
import dev.ohs.fhir.engine.benchmark.Workload
import dev.ohs.fhir.engine.delete
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString

/**
 * CRUD workloads, porting android-fhir's `CrudApiViewModel`. Each performs hundreds of operations
 * per iteration to clear Android's trace noise floor.
 */
object CrudWorkloads {

  fun all(): List<Workload> =
    listOf(
      CreateBatch,
      CreateStreamingAll,
      GetById,
      Update,
      Delete,
      TransactionBatch,
    )

  /** All patients in one varargs call, so the engine can batch the insert. */
  private object CreateBatch : Workload {
    override val id = "crud.create_batch"
    override val group = "crud"
    override var opsPerIteration = 1
    override val isolation = Isolation.FRESH_DATABASE

    override suspend fun prepare(env: BenchmarkEnv) {
      opsPerIteration = env.dataset.patientIds.size
    }

    override suspend fun run(env: BenchmarkEnv) {
      env.engine.create(*env.dataset.allResources.filterIsInstance<Patient>().toTypedArray())
    }
  }

  /** Every resource in the dataset, one call each. The bulk-import path an app actually hits. */
  private object CreateStreamingAll : Workload {
    override val id = "crud.create_streaming_all"
    override val group = "crud"
    override var opsPerIteration = 1
    override val isolation = Isolation.FRESH_DATABASE

    override suspend fun prepare(env: BenchmarkEnv) {
      opsPerIteration = env.dataset.allResources.size
    }

    override suspend fun run(env: BenchmarkEnv) {
      for (resource in env.dataset.allResources) {
        env.engine.create(resource)
      }
    }
  }

  private object GetById : Workload {
    override val id = "crud.get_by_id"
    override val group = "crud"
    override var opsPerIteration = OPERATION_COUNT
    override val isolation = Isolation.NONE

    private var targets: List<String> = emptyList()

    override suspend fun prepare(env: BenchmarkEnv) {
      env.seedDatasetIfEmpty()
      targets = env.crudTargetIds(OPERATION_COUNT)
      opsPerIteration = targets.size
    }

    override suspend fun run(env: BenchmarkEnv) {
      for (id in targets) {
        env.engine.get<Patient>(id)
      }
    }
  }

  private object Update : Workload {
    override val id = "crud.update"
    override val group = "crud"
    override var opsPerIteration = OPERATION_COUNT
    override val isolation = Isolation.NONE

    private var targets: List<Patient> = emptyList()

    override suspend fun prepare(env: BenchmarkEnv) {
      env.seedDatasetIfEmpty()
      val ids = env.crudTargetIds(OPERATION_COUNT)
      targets = ids.map { env.engine.get<Patient>(it) }
      opsPerIteration = targets.size
    }

    // The engine skips updates matching the stored copy, so a toggled boolean leaves this
    // measuring a mix of real updates and no-ops. A monotonic revision guarantees a diff.
    private var revision = 0

    override suspend fun beforeEach(env: BenchmarkEnv) {
      revision++
    }

    override suspend fun run(env: BenchmarkEnv) {
      for (patient in targets) {
        env.engine.update(
          patient.copy(
            name =
              listOf(
                HumanName(
                  family = FhirString(value = "Revision$revision"),
                  given = listOf(FhirString(value = "Benchmark")),
                ),
              ),
          ),
        )
      }
    }
  }

  private object Delete : Workload {
    override val id = "crud.delete"
    override val group = "crud"
    override var opsPerIteration = OPERATION_COUNT
    override val isolation = Isolation.CLEAR_TABLES

    private var targets: List<String> = emptyList()

    override suspend fun prepare(env: BenchmarkEnv) {
      targets = env.crudTargetIds(OPERATION_COUNT)
      opsPerIteration = targets.size
    }

    // Deleting empties the tables, so every iteration has to put them back. Untimed.
    override suspend fun beforeEach(env: BenchmarkEnv) {
      env.seedDataset()
    }

    override suspend fun run(env: BenchmarkEnv) {
      for (id in targets) {
        env.engine.delete<Patient>(id)
      }
    }
  }

  /** The same inserts as [CreateBatch], wrapped in one transaction. */
  private object TransactionBatch : Workload {
    override val id = "crud.transaction_batch"
    override val group = "crud"
    override var opsPerIteration = 1
    override val isolation = Isolation.FRESH_DATABASE

    override suspend fun prepare(env: BenchmarkEnv) {
      opsPerIteration = env.dataset.allResources.size
    }

    override suspend fun run(env: BenchmarkEnv) {
      env.engine.withTransaction {
        for (resource in env.dataset.allResources) {
          create(resource)
        }
      }
    }
  }

  /** Clear of the trace noise floor without dominating a run. */
  private const val OPERATION_COUNT = 500
}
