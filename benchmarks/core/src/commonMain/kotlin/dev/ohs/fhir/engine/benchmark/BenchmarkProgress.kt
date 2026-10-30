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

/**
 * Where a run has got to. Events fire outside [benchmarkSpan], but work a listener schedules
 * elsewhere can land inside the next span; keep the listener empty for clean numbers.
 */
sealed interface BenchmarkProgress {

  /** Reading and parsing the corpus. */
  data class LoadingDataset(val datasetKind: String) : BenchmarkProgress

  /** The loaded corpus: its kind, population and fingerprint. */
  data class DatasetReady(val manifest: DatasetManifest) : BenchmarkProgress

  /** The catalogue is selected. */
  data class RunStarted(val totalWorkloads: Int) : BenchmarkProgress

  /** Untimed per-workload setup, which seeds the corpus into the engine. */
  data class Preparing(
    val workloadId: String,
    val group: String,
    val index: Int,
    val total: Int,
  ) : BenchmarkProgress

  /** One iteration is about to start. [iteration] is zero-based across warmup, then measured. */
  data class Iterating(
    val workloadId: String,
    val group: String,
    val index: Int,
    val total: Int,
    val iteration: Int,
    val warmupIterations: Int,
    val measuredIterations: Int,
  ) : BenchmarkProgress {
    val isWarmup: Boolean
      get() = iteration < warmupIterations
  }

  /** A workload ended. [result] carries the error when it failed. */
  data class WorkloadFinished(
    val result: WorkloadResult,
    val index: Int,
    val total: Int,
  ) : BenchmarkProgress

  /** The report has been written. */
  data class RunFinished(val report: BenchmarkReport) : BenchmarkProgress
}

/** Called from the benchmark coroutine, on whichever thread the run happens to be on. */
typealias ProgressListener = (BenchmarkProgress) -> Unit
