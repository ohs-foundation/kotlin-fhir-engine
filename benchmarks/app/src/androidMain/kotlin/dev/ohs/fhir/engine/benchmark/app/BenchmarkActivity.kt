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
 * The driver's only screen, launched by intent extras rather than UI taps, which break whenever the
 * UI moves.
 *
 * Two very different displays. A single-workload launch is macrobenchmark's, and keeps a bare
 * [TextView] whose resource id UI Automator selects on — no Compose is initialised on that path. A
 * group launch runs for hours and gets the Compose progress screen instead; nothing waits on its
 * status, so it is free to render whatever is useful.
 */
class BenchmarkActivity : ComponentActivity() {

  /**
   * Off the main thread: a long workload there triggers an ANR dialog, which contaminates the
   * measurement and hides the status view from UI Automator.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Kept so a relaunch can cancel it. Otherwise two runs race and whichever finishes last writes
   * the status, letting a stale [STATUS_DONE] overwrite a newer failure.
   */
  private var runJob: Job? = null
  private var tickerJob: Job? = null

  /**
   * Bumped per launch. Cancellation is cooperative, so a superseded run keeps executing until its
   * next suspension point; everything it does after that — progress events, stopping the clock,
   * writing a status — belongs to a display it no longer owns and has to be dropped.
   */
  @Volatile private var runEpoch = 0

  private val runState = MutableStateFlow(RunState())
  private val elapsedSeconds = MutableStateFlow(0L)

  /** Only inflated on the single-workload path. Null in group mode. */
  private var statusView: TextView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // A long workload outlasts the screen timeout, and a sleeping device draws no frames, so
    // macrobenchmark's startActivityAndWait never sees the launch complete.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setShowWhenLocked(true)
    setTurnScreenOn(true)

    AndroidBenchmarkContext.context = applicationContext

    start(intent)
  }

  /**
   * The activity is `singleTop`, so a relaunch reuses this instance. Resetting the display here
   * stops a harness reading the previous run's [STATUS_DONE].
   */
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
          )
          .associateWith { intent.getStringExtra(it) },
      )

    runJob?.cancel()
    tickerJob?.cancel()
    val epoch = ++runEpoch

    // Chosen here rather than in execute(): setContent and setContentView must run on the main
    // thread, and onCreate/onNewIntent are already on it.
    if (request.workloadId != null) showStatusView() else showProgressScreen()

    runJob = scope.launch { execute(request, epoch) }
  }

  private fun isCurrent(epoch: Int) = epoch == runEpoch

  /** Only the run that owns the display may stop the clock. */
  private fun stopTicker(epoch: Int) {
    if (isCurrent(epoch)) tickerJob?.cancel()
  }

  /** Macrobenchmark's path. The resource id is what `By.res(pkg, "benchmark_status")` selects. */
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
    // Ticks through measured spans. Accepted: group mode is the secondary Android stream —
    // macrobenchmark is authoritative — and a frozen clock during a long iteration looks like the
    // hang this screen exists to rule out.
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
        // STATUS_READY marks the end of untimed setup, before any measured work.
        val run = BenchmarkDriver.prepareSingle(request)
        // Logged because this path writes no report, and asking for synthea does not guarantee
        // getting it: a build without the staged assets falls back to synthetic silently.
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
            // Completions only. Logging every iteration floods logcat on a 26-workload run.
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
      // In the view as well as the log, or a harness waiting on STATUS_DONE just times out.
      Log.e(TAG, "benchmark failed", e)
      if (!isCurrent(epoch)) return
      val message = "${e::class.simpleName}: ${e.message}"
      stopTicker(epoch)
      runState.update { it.failed(message) }
      setStatus("$STATUS_FAILED $message")
    }
  }

  /**
   * No-ops in group mode, where there is no status view. Never touches the view off the UI thread.
   */
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
