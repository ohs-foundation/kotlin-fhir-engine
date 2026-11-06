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
package dev.ohs.fhir.engine.benchmark.workloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Macrobenchmark restarts the process between iterations, so upload ids must differ across
 * processes; a repeated id turns the measured create into an update.
 */
class ServerWorkloadsTest {

  @Test
  fun `ids from two processes do not collide`() {
    val first = ServerWorkloads.uploadIds(PREFIX, "2026-08-29T10:00:00.000Z", 0, COUNT)
    val second = ServerWorkloads.uploadIds(PREFIX, "2026-08-29T10:05:00.000Z", 0, COUNT)

    assertEquals(emptySet(), first.toSet() intersect second.toSet())
  }

  @Test
  fun `ids from two iterations in one process do not collide`() {
    val first = ServerWorkloads.uploadIds(PREFIX, TOKEN, 0, COUNT)
    val second = ServerWorkloads.uploadIds(PREFIX, TOKEN, 1, COUNT)

    assertEquals(emptySet(), first.toSet() intersect second.toSet())
  }

  @Test
  fun `ids within one iteration are distinct`() {
    val ids = ServerWorkloads.uploadIds(PREFIX, TOKEN, 0, COUNT)

    assertEquals(COUNT, ids.size)
    assertEquals(COUNT, ids.toSet().size)
  }

  @Test
  fun `ids are legal fhir ids`() {
    // The run token is a timestamp; `:` and `+` are not valid in FHIR ids.
    val ids = ServerWorkloads.uploadIds(PREFIX, "2026-08-29T10:00:00.000+03:00", 0, COUNT)

    val legal = Regex("[A-Za-z0-9.-]{1,64}")
    assertTrue(
      ids.all { legal.matches(it) },
      "Not FHIR ids: ${ids.filterNot { legal.matches(it) }}",
    )
  }

  private companion object {
    const val PREFIX = "bench-upload"
    const val TOKEN = "2026-08-29T10:00:00.000Z"
    const val COUNT = 4
  }
}
