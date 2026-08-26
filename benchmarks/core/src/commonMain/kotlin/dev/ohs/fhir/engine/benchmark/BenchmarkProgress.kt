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
 * Where a run has got to.
 *
 * Every event is emitted outside the measured span: [BenchmarkRunner] wraps only `Workload.run` in
 * [benchmarkSpan], and these fire around it. A listener that blocks stretches the untimed gaps
 * between iterations, so keep implementations cheap.
 *
 * That covers what a listener costs synchronously, not what it starts. A listener that schedules
 * work elsewhere — a Compose recomposition on the main thread, say — can have that work land inside
 * the next span, because an `Isolation.NONE` workload does almost nothing between [Iterating] and
 * the span opening. The Android driver app accepts this: macrobenchmark is the authoritative
 * Android measurement and never runs a listener. Anything that wants clean numbers from the
 * in-process runner should keep the listener empty.
 */
sealed interface BenchmarkProgress {

  /** Reading and parsing the corpus. On a Synthea run this is the longest silent stretch. */
  data class LoadingDataset(val datasetKind: String) : BenchmarkProgress

  /** What actually loaded, which is synthetic whenever the Synthea data was not staged. */
  data class DatasetReady(val manifest: DatasetManifest) : BenchmarkProgress

  /** The catalogue is selected, so a screen can show a denominator before workload one starts. */
  data class RunStarted(val totalWorkloads: Int) : BenchmarkProgress

  /** Untimed per-workload setup, which seeds the corpus into the engine. Minutes on Synthea. */
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

  /** A workload ended. [result] carries the error when it failed, so failures are visible. */
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
