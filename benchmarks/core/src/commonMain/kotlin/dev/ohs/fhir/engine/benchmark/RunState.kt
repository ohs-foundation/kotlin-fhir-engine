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

/** Coarse stage, for a display that wants one word. */
enum class RunPhase {
  STARTING,
  LOADING_DATASET,
  PREPARING,
  MEASURING,

  /** The last workload is done and the report is being serialised and written. */
  REPORTING,
  DONE,
  FAILED,
  ;

  val isTerminal: Boolean
    get() = this == DONE || this == FAILED
}

/** The workload in flight. */
data class CurrentWorkload(
  val id: String,
  val group: String,
  val index: Int,
  val total: Int,
  /** Null while preparing; set once iterations begin. */
  val iterationLabel: String? = null,
)

/** A workload that ended, successfully or not. */
data class FinishedWorkload(
  val id: String,
  val group: String,
  val medianMillis: Double,
  val medianMillisPerOp: Double,
  val error: String? = null,
) {
  val succeeded: Boolean
    get() = error == null
}

/**
 * Everything a progress display needs, folded from [BenchmarkProgress].
 *
 * Kept here rather than in the driver app so it can be unit tested without a device, and so a
 * console renderer for desktop or iOS can reuse it later.
 */
data class RunState(
  val phase: RunPhase = RunPhase.STARTING,
  val datasetLabel: String? = null,
  val totalWorkloads: Int = 0,
  val current: CurrentWorkload? = null,
  /** Completion order, which is catalogue order, so this reads as the results table. */
  val finished: List<FinishedWorkload> = emptyList(),
  val report: BenchmarkReport? = null,
  val failure: String? = null,
) {

  val completedWorkloads: Int
    get() = finished.size

  fun fold(event: BenchmarkProgress): RunState =
    when (event) {
      is BenchmarkProgress.LoadingDataset ->
        copy(phase = RunPhase.LOADING_DATASET, datasetLabel = event.datasetKind)

      // Overwrites the requested kind: asking for synthea does not guarantee getting it.
      is BenchmarkProgress.DatasetReady ->
        copy(datasetLabel = "${event.manifest.kind} ${event.manifest.population}pt")
      is BenchmarkProgress.RunStarted ->
        copy(phase = RunPhase.PREPARING, totalWorkloads = event.totalWorkloads)
      is BenchmarkProgress.Preparing ->
        copy(
          phase = RunPhase.PREPARING,
          totalWorkloads = event.total,
          current = CurrentWorkload(event.workloadId, event.group, event.index, event.total),
        )
      is BenchmarkProgress.Iterating ->
        copy(
          phase = RunPhase.MEASURING,
          totalWorkloads = event.total,
          current =
            CurrentWorkload(
              id = event.workloadId,
              group = event.group,
              index = event.index,
              total = event.total,
              iterationLabel = iterationLabel(event),
            ),
        )
      is BenchmarkProgress.WorkloadFinished ->
        copy(
          // Not left on MEASURING: the runner moves straight to the next workload's untimed setup,
          // and after the last one there is nothing to name until the report lands.
          phase = if (event.index + 1 >= event.total) RunPhase.REPORTING else RunPhase.PREPARING,
          totalWorkloads = event.total,
          current = null,
          finished =
            finished +
              FinishedWorkload(
                id = event.result.id,
                group = event.result.group,
                medianMillis = event.result.statistics.median,
                medianMillisPerOp = event.result.medianMillisPerOp,
                error = event.result.error,
              ),
        )
      is BenchmarkProgress.RunFinished ->
        copy(phase = RunPhase.DONE, current = null, report = event.report)
    }

  /** For a run that threw before [BenchmarkProgress.RunFinished]. Keeps whatever finished. */
  fun failed(message: String): RunState =
    copy(phase = RunPhase.FAILED, current = null, failure = message)

  private fun iterationLabel(event: BenchmarkProgress.Iterating): String =
    if (event.isWarmup) {
      "warmup ${event.iteration + 1}/${event.warmupIterations}"
    } else {
      "measured ${event.iteration - event.warmupIterations + 1}/${event.measuredIterations}"
    }
}
