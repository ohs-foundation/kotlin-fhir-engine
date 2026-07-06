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
package dev.ohs.fhir

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Unit tests for [FhirEngineProvider]. */
class FhirEngineProviderTest {

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.clearInstance()
  }

  @Test
  fun getInstance_calledTwice_shouldReturnSameFhirEngine() {
    FhirEngineProvider.init(FhirEngineConfiguration())
    val engineOne = FhirEngineProvider.getInstance()
    val engineTwo = FhirEngineProvider.getInstance()
    assertSame(engineOne, engineTwo)
  }

  @Test
  fun getInstance_afterClearInstance_shouldReturnDifferentInstances() {
    FhirEngineProvider.init(FhirEngineConfiguration(testMode = true))
    val engineOne = FhirEngineProvider.getInstance()
    FhirEngineProvider.clearInstance()
    FhirEngineProvider.init(FhirEngineConfiguration(testMode = true))
    val engineTwo = FhirEngineProvider.getInstance()
    assertNotSame(engineOne, engineTwo)
  }

  @Test
  fun createFhirEngineConfiguration_withDefaultNetworkConfig_shouldHaveDefaultTimeout() {
    val config = FhirEngineConfiguration(serverConfiguration = ServerConfiguration(""))
    with(config.serverConfiguration!!.networkConfiguration) {
      assertEquals(10L, connectionTimeOut)
      assertEquals(10L, readTimeOut)
      assertEquals(10L, writeTimeOut)
    }
  }

  @Test
  fun createFhirEngineConfiguration_configureNetworkTimeouts_shouldHaveConfiguredTimeout() {
    val config =
      FhirEngineConfiguration(
        serverConfiguration =
          ServerConfiguration(
            "",
            NetworkConfiguration(connectionTimeOut = 5, readTimeOut = 4, writeTimeOut = 6),
          ),
      )
    with(config.serverConfiguration!!.networkConfiguration) {
      assertEquals(5L, connectionTimeOut)
      assertEquals(4L, readTimeOut)
      assertEquals(6L, writeTimeOut)
    }
  }

  @Test
  fun createFhirEngineConfiguration_configureHttpCache_shouldHaveCacheConfiguration() {
    val config =
      FhirEngineConfiguration(
        serverConfiguration =
          ServerConfiguration(
            "",
            NetworkConfiguration(
              httpCache =
                CacheConfiguration(
                  cacheDir = "sample-dir/http_cache",
                  maxSize = 50L * 1024L * 1024L,
                ),
            ),
          ),
      )
    with(config.serverConfiguration!!.networkConfiguration) {
      assertEquals(50L * 1024L * 1024L, httpCache?.maxSize)
      assertEquals("sample-dir/http_cache", httpCache?.cacheDir)
    }
  }
}
