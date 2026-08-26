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

import androidx.tracing.Trace
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * One dedicated thread, because atrace pairs begin and end per thread.
 *
 * The measured block suspends constantly — every engine call does — so on a multi-threaded
 * dispatcher it resumes on whichever thread is free and [Trace.endSection] lands somewhere other
 * than [Trace.beginSection]. The section then never closes, and `TraceSectionMetric` reports a
 * count of zero while the trace still contains the name.
 */
private val spanDispatcher =
  Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "benchmark-span") }
    .asCoroutineDispatcher()

/**
 * Emits an `atrace` section that macrobenchmark's `TraceSectionMetric` reads back out of the
 * Perfetto trace. The section name is the workload id, which is also the key in the JSON report.
 *
 * Section names are truncated by the platform at 127 characters; workload ids are far shorter.
 */
internal actual suspend fun <T> benchmarkSpan(name: String, block: suspend () -> T): T =
  withContext(spanDispatcher) {
    Trace.beginSection(name)
    try {
      block()
    } finally {
      Trace.endSection()
    }
  }
