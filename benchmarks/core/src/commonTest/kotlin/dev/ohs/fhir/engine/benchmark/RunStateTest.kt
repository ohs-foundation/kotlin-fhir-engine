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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunStateTest {

  private fun manifest() =
    DatasetManifest(
      kind = "synthea",
      population = 121,
      seed = 1,
      resourceCounts = mapOf("Patient" to 121),
      fingerprint = "abc123",
    )

  private fun result(id: String, median: Double, error: String? = null) =
    WorkloadResult(
      id = id,
      group = "search",
      opsPerIteration = 10,
      isolationApplied = "clear_tables",
      samplesMillis = listOf(median),
      statistics = Statistics(median, median, median, median, median, 0.0),
      medianMillisPerOp = median / 10,
      error = error,
    )

  private fun fold(vararg events: BenchmarkProgress) =
    events.fold(RunState()) { state, event -> state.fold(event) }

  @Test
  fun startsInStartingPhase() {
    assertEquals(RunPhase.STARTING, RunState().phase)
    assertEquals(0, RunState().completedWorkloads)
  }

  @Test
  fun loadingDatasetShowsRequestedKind() {
    val state = fold(BenchmarkProgress.LoadingDataset("synthea"))
    assertEquals(RunPhase.LOADING_DATASET, state.phase)
    assertEquals("synthea", state.datasetLabel)
  }

  @Test
  fun datasetReadyReplacesLabelWithWhatActuallyLoaded() {
    val state =
      fold(
        BenchmarkProgress.LoadingDataset("synthea"),
        BenchmarkProgress.DatasetReady(manifest()),
      )
    assertEquals("synthea 121pt", state.datasetLabel)
  }

  @Test
  fun runStartedRecordsTheTotal() {
    val state = fold(BenchmarkProgress.RunStarted(26))
    assertEquals(RunPhase.PREPARING, state.phase)
    assertEquals(26, state.totalWorkloads)
  }

  @Test
  fun preparingHasNoIterationLabelYet() {
    val state = fold(BenchmarkProgress.Preparing("search.by_code", "search", 0, 26))
    assertEquals(RunPhase.PREPARING, state.phase)
    assertEquals("search.by_code", state.current?.id)
    assertNull(state.current?.iterationLabel)
  }

  @Test
  fun warmupIterationsAreLabelledOneBased() {
    val state = fold(BenchmarkProgress.Iterating("search.by_code", "search", 0, 26, 0, 2, 5))
    assertEquals(RunPhase.MEASURING, state.phase)
    assertEquals("warmup 1/2", state.current?.iterationLabel)
  }

  @Test
  fun measuredIterationsCountFromTheEndOfWarmup() {
    val state = fold(BenchmarkProgress.Iterating("search.by_code", "search", 0, 26, 2, 2, 5))
    assertEquals("measured 1/5", state.current?.iterationLabel)
  }

  @Test
  fun lastMeasuredIterationIsLabelledCorrectly() {
    val state = fold(BenchmarkProgress.Iterating("search.by_code", "search", 0, 26, 6, 2, 5))
    assertEquals("measured 5/5", state.current?.iterationLabel)
  }

  @Test
  fun finishedWorkloadsAccumulateInCatalogueOrder() {
    val state =
      fold(
        BenchmarkProgress.RunStarted(2),
        BenchmarkProgress.WorkloadFinished(result("crud.create", 6000.0), 0, 2),
        BenchmarkProgress.WorkloadFinished(result("search.by_code", 48.0), 1, 2),
      )
    assertEquals(listOf("crud.create", "search.by_code"), state.finished.map { it.id })
    assertEquals(2, state.completedWorkloads)
    assertNull(state.current)
  }

  @Test
  fun finishingAWorkloadMidRunGoesBackToPreparing() {
    // The runner prepares the next workload straight away, so leaving the phase on MEASURING
    // would claim a measurement is in flight when none is.
    val state =
      fold(
        BenchmarkProgress.Iterating("crud.create", "crud", 0, 2, 0, 1, 2),
        BenchmarkProgress.WorkloadFinished(result("crud.create", 6000.0), 0, 2),
      )
    assertEquals(RunPhase.PREPARING, state.phase)
  }

  @Test
  fun finishingTheLastWorkloadShowsReporting() {
    // Serialising and writing the report takes long enough to look like a hang, and it is the one
    // stretch with no current workload to name.
    val state =
      fold(
        BenchmarkProgress.Iterating("search.by_code", "search", 1, 2, 0, 1, 2),
        BenchmarkProgress.WorkloadFinished(result("search.by_code", 48.0), 1, 2),
      )
    assertEquals(RunPhase.REPORTING, state.phase)
    assertTrue(!state.phase.isTerminal)
  }

  @Test
  fun aFailedWorkloadIsRecordedRatherThanDropped() {
    val state = fold(BenchmarkProgress.WorkloadFinished(result("server.upload", 0.0, "boom"), 0, 1))
    assertEquals(1, state.finished.size)
    assertEquals("boom", state.finished.single().error)
    assertTrue(!state.finished.single().succeeded)
  }

  @Test
  fun runFinishedKeepsTheReport() {
    val report =
      BenchmarkReport(
        timestamp = "2026-08-26T00:00:00Z",
        platform = PlatformDescriptor(target = "android", os = "Android 16"),
        config = BenchmarkConfig.of(),
        dataset = manifest(),
        results = listOf(result("search.by_code", 48.0)),
      )
    val state = fold(BenchmarkProgress.RunFinished(report))
    assertEquals(RunPhase.DONE, state.phase)
    assertEquals(report, state.report)
    assertTrue(state.phase.isTerminal)
  }

  @Test
  fun failedClearsTheInFlightWorkloadAndKeepsWhatFinished() {
    val state =
      fold(
          BenchmarkProgress.WorkloadFinished(result("crud.create", 6000.0), 0, 2),
          BenchmarkProgress.Iterating("search.by_code", "search", 1, 2, 0, 2, 5),
        )
        .failed("OutOfMemoryError: heap")
    assertEquals(RunPhase.FAILED, state.phase)
    assertNull(state.current)
    assertEquals("OutOfMemoryError: heap", state.failure)
    assertEquals(1, state.finished.size)
  }

  @Test
  fun aRealRunEmitsEventsInAnOrderThatFoldsToACompleteState() {
    // Mirrors the emission order in BenchmarkRunner: RunStarted, then Preparing/Iterating per
    // workload, then WorkloadFinished, then RunFinished. Guards against an emission moving.
    val report =
      BenchmarkReport(
        timestamp = "2026-08-26T00:00:00Z",
        platform = PlatformDescriptor(target = "desktop", os = "test"),
        config = BenchmarkConfig.of(warmupIterations = 1, measuredIterations = 2),
        dataset = manifest(),
        results = listOf(result("search.by_code", 48.0)),
      )
    val state =
      fold(
        BenchmarkProgress.LoadingDataset("synthea"),
        BenchmarkProgress.DatasetReady(manifest()),
        BenchmarkProgress.RunStarted(1),
        BenchmarkProgress.Preparing("search.by_code", "search", 0, 1),
        BenchmarkProgress.Iterating("search.by_code", "search", 0, 1, 0, 1, 2),
        BenchmarkProgress.Iterating("search.by_code", "search", 0, 1, 1, 1, 2),
        BenchmarkProgress.Iterating("search.by_code", "search", 0, 1, 2, 1, 2),
        BenchmarkProgress.WorkloadFinished(result("search.by_code", 48.0), 0, 1),
        BenchmarkProgress.RunFinished(report),
      )
    assertEquals(RunPhase.DONE, state.phase)
    assertEquals(1, state.totalWorkloads)
    assertEquals(1, state.completedWorkloads)
    assertEquals("synthea 121pt", state.datasetLabel)
    assertNull(state.current)
  }
}
