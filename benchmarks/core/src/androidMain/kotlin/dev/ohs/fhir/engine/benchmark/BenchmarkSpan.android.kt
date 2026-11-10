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
 * A single thread, because atrace pairs [Trace.beginSection] and [Trace.endSection] per thread and
 * the measured block suspends.
 */
private val spanDispatcher =
  Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "benchmark-span") }
    .asCoroutineDispatcher()

/**
 * Emits an `atrace` section named [name] for macrobenchmark's `TraceSectionMetric`. The platform
 * truncates section names at 127 characters.
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
