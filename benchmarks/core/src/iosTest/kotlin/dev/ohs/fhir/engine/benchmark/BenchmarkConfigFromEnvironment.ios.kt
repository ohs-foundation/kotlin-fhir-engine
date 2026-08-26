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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Read from the environment, which the Gradle test task fills from the `-Pbenchmark.*` properties.
 *
 * The defaults are deliberately small: a simulator harness is a check that the engine works on the
 * platform, not a source of headline numbers.
 */
@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? =
  getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }

internal actual suspend fun benchmarkConfigFromEnvironment(): BenchmarkConfig =
  BenchmarkConfig.of(
    profile = Profile.fromString(env("BENCHMARK_PROFILE") ?: Profile.SMOKE.name),
    datasetKind =
      if (env("BENCHMARK_DATASET").equals("synthea", ignoreCase = true)) {
        DatasetKind.SYNTHEA
      } else {
        DatasetKind.SYNTHETIC
      },
    seed = env("BENCHMARK_SEED")?.toIntOrNull() ?: BenchmarkConfig.DEFAULT_SEED,
    warmupIterations = env("BENCHMARK_WARMUP")?.toIntOrNull() ?: 1,
    measuredIterations = env("BENCHMARK_ITERATIONS")?.toIntOrNull() ?: 3,
    groups = env("BENCHMARK_GROUPS")?.split(",")?.map { it.trim() }
        ?: listOf("crud", "search", "sync"),
    serverUrl = env("BENCHMARK_SERVER"),
  )
