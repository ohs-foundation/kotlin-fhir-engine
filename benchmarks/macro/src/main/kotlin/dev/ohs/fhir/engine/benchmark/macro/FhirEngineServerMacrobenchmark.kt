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
package dev.ohs.fhir.engine.benchmark.macro

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ohs.fhir.engine.benchmark.workloads.Workloads
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upload and download against a real FHIR server. Results include the server and the network.
 *
 * A server on the host needs `adb reverse tcp:8080 tcp:8080` for the device to reach `localhost`.
 */
@RunWith(AndroidJUnit4::class)
class FhirEngineServerMacrobenchmark : FhirEngineMacrobenchmark() {

  @Test
  fun server() {
    // Skipped, not failed, when no server is configured.
    assumeTrue(
      "No -Pbenchmark.server, so there is nothing to sync against.",
      SERVER.isNotBlank(),
    )
    Workloads.byGroup("server").filter { ONLY == null || it.id == ONLY }.forEach { measure(it) }
  }
}
