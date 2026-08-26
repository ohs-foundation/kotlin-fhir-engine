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

internal actual suspend fun benchmarkConfigFromEnvironment(): BenchmarkConfig =
  BenchmarkConfig.of(
    profile = Profile.fromString(System.getProperty("benchmark.profile")),
    datasetKind =
      if (System.getProperty("benchmark.dataset").equals("synthea", ignoreCase = true)) {
        DatasetKind.SYNTHEA
      } else {
        DatasetKind.SYNTHETIC
      },
    seed = System.getProperty("benchmark.seed")?.toIntOrNull() ?: BenchmarkConfig.DEFAULT_SEED,
    warmupIterations = System.getProperty("benchmark.warmup")?.toIntOrNull() ?: 2,
    measuredIterations = System.getProperty("benchmark.iterations")?.toIntOrNull() ?: 5,
    groups = System.getProperty("benchmark.groups")?.split(",")?.map { it.trim() }
        ?: listOf("crud", "search", "sync"),
    serverUrl = System.getProperty("benchmark.server")?.takeIf { it.isNotBlank() },
  )
