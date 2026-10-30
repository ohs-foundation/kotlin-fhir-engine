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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BenchmarkRequestTest {

  @Test
  fun `reads the server url`() {
    val request = BenchmarkRequest.from(mapOf(BenchmarkRequest.KEY_SERVER to SERVER))

    assertEquals(SERVER, request.serverUrl)
  }

  @Test
  fun `treats a blank server url as absent`() {
    // The macrobenchmark harness passes an unset property as "".
    val request = BenchmarkRequest.from(mapOf(BenchmarkRequest.KEY_SERVER to "  "))

    assertNull(request.serverUrl)
  }

  @Test
  fun `has no server url when the key is missing`() {
    assertNull(BenchmarkRequest.from(emptyMap()).serverUrl)
  }

  @Test
  fun `carries the server url into the config the harness runs`() {
    val config = BenchmarkRequest.from(mapOf(BenchmarkRequest.KEY_SERVER to SERVER)).toConfig()

    // BenchmarkConfig adds the trailing slash the engine expects.
    assertEquals("$SERVER/", config.serverUrl)
  }

  @Test
  fun `leaves the config without a server when none was given`() {
    assertNull(BenchmarkRequest.from(emptyMap()).toConfig().serverUrl)
  }

  @Test
  fun `defaults to the server-free groups`() {
    assertEquals(listOf("crud", "search", "sync"), BenchmarkRequest.from(emptyMap()).groups)
  }

  @Test
  fun `reads an explicit list of workloads`() {
    val request =
      BenchmarkRequest.from(
        mapOf(BenchmarkRequest.KEY_WORKLOADS to "crud.update, sync.download_batch"),
      )

    assertEquals(listOf("crud.update", "sync.download_batch"), request.workloadIds)
  }

  @Test
  fun `has no explicit workloads when the key is missing`() {
    assertEquals(emptyList(), BenchmarkRequest.from(emptyMap()).workloadIds)
  }

  @Test
  fun `ignores a blank workload list`() {
    assertEquals(
      emptyList(),
      BenchmarkRequest.from(mapOf(BenchmarkRequest.KEY_WORKLOADS to " , ")).workloadIds,
    )
  }

  @Test
  fun `carries the explicit workloads into the config`() {
    val config =
      BenchmarkRequest.from(mapOf(BenchmarkRequest.KEY_WORKLOADS to "crud.update")).toConfig()

    assertEquals(listOf("crud.update"), config.workloadIds)
  }

  private companion object {
    const val SERVER = "http://localhost:8080/fhir"
  }
}
