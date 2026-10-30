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
package dev.ohs.fhir.engine.benchmark.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.ohs.fhir.engine.benchmark.AndroidBenchmarkContext
import dev.ohs.fhir.engine.benchmark.BenchmarkProgress
import dev.ohs.fhir.engine.benchmark.RunState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The driver's only screen, configured by intent extras.
 *
 * A single-workload launch shows a bare [TextView] that UI Automator selects, with no Compose. A
 * group launch shows the Compose progress screen.
 */
class BenchmarkActivity : ComponentActivity() {

  /** Off the main thread: an ANR dialog would hide the status view from UI Automator. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Cancelled on relaunch so a stale run cannot write the status. */
  private var runJob: Job? = null
  private var tickerJob: Job? = null

  /**
   * Bumped per launch. A cancelled run executes until its next suspension point; its display
   * updates are dropped when its epoch is stale.
   */
  @Volatile private var runEpoch = 0

  private val runState = MutableStateFlow(RunState())
  private val elapsedSeconds = MutableStateFlow(0L)

  /** Null in group mode. */
  private var statusView: TextView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // A sleeping device draws no frames, so startActivityAndWait never sees the launch complete.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setShowWhenLocked(true)
    setTurnScreenOn(true)

    AndroidBenchmarkContext.context = applicationContext

    start(intent)
  }

  /** `singleTop` relaunches reuse this instance; the display is reset for the new run. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    start(intent)
  }

  private fun start(intent: Intent) {
    val request =
      BenchmarkRequest.from(
        listOf(
            BenchmarkRequest.KEY_WORKLOAD,
            BenchmarkRequest.KEY_GROUPS,
            BenchmarkRequest.KEY_PROFILE,
            BenchmarkRequest.KEY_DATASET,
            BenchmarkRequest.KEY_WARMUP,
            BenchmarkRequest.KEY_ITERATIONS,
            BenchmarkRequest.KEY_SERVER,
            BenchmarkRequest.KEY_WORKLOADS,
            BenchmarkRequest.KEY_MIX,
          )
          .associateWith { intent.getStringExtra(it) },
      )

    runJob?.cancel()
    tickerJob?.cancel()
    val epoch = ++runEpoch

    // setContent and setContentView must run on the main thread.
    if (request.workloadId != null) showStatusView() else showProgressScreen()

    runJob = scope.launch { execute(request, epoch) }
  }

  private fun isCurrent(epoch: Int) = epoch == runEpoch

  /** Only the run that owns the display may stop the clock. */
  private fun stopTicker(epoch: Int) {
    if (isCurrent(epoch)) tickerJob?.cancel()
  }

  /** Selected by `By.res(pkg, "benchmark_status")`. */
  private fun showStatusView() {
    val view =
      statusView
        ?: TextView(this)
          .apply {
            id = R.id.benchmark_status
            gravity = Gravity.CENTER
            textSize = 18f
          }
          .also { statusView = it }
    setContentView(view)
    setStatus(STATUS_STARTING)
  }

  private fun showProgressScreen() {
    statusView = null
    runState.value = RunState()
    elapsedSeconds.value = 0L
    setContent {
      val state by runState.collectAsState()
      val elapsed by elapsedSeconds.collectAsState()
      ProgressScreen(state, elapsed)
    }
    // Ticks through measured spans so a long iteration does not look hung.
    tickerJob =
      scope.launch {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
          elapsedSeconds.value = start.elapsedNow().inWholeSeconds
          delay(1000)
        }
      }
  }

  private suspend fun execute(request: BenchmarkRequest, epoch: Int) {
    try {
      if (request.workloadId != null) {
        // STATUS_READY marks the end of untimed setup.
        val run = BenchmarkDriver.prepareSingle(request)
        // This path writes no report, so the log is the only record of the corpus.
        run.datasetManifest.let {
          Log.i(TAG, "dataset=${it.kind} population=${it.population} fingerprint=${it.fingerprint}")
        }
        setStatus(STATUS_READY)
        run.beforeEach()
        run.measureOnce()
        run.afterEach()
        setStatus(STATUS_DONE)
        Log.i(TAG, "completed ${request.workloadId}")
      } else {
        val report =
          BenchmarkDriver.runAll(request) { event ->
            if (!isCurrent(epoch)) return@runAll
            runState.update { it.fold(event) }
            // Completions only, to keep logcat readable.
            if (event is BenchmarkProgress.WorkloadFinished) {
              Log.i(
                TAG,
                "${event.index + 1}/${event.total} ${event.result.id} " +
                  (event.result.error?.let { "ERROR $it" }
                    ?: "median=${event.result.statistics.median} ms"),
              )
            }
          }
        stopTicker(epoch)
        Log.i(TAG, BenchmarkDriver.summarise(report))
      }
    } catch (e: CancellationException) {
      // A newer launch owns the display now; a failure here would overwrite it.
      throw e
    } catch (e: Throwable) {
      // Shown in the view so a waiting harness sees the failure.
      Log.e(TAG, "benchmark failed", e)
      if (!isCurrent(epoch)) return
      val message = "${e::class.simpleName}: ${e.message}"
      stopTicker(epoch)
      runState.update { it.failed(message) }
      setStatus("$STATUS_FAILED $message")
    }
  }

  /** No-op in group mode. */
  private fun setStatus(status: String) {
    val view = statusView ?: return
    runOnUiThread { view.text = status }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    const val TAG = "BenchmarkDriver"

    const val STATUS_STARTING = "starting"
    const val STATUS_READY = "ready"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
  }
}
