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
package dev.ohs.fhir.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

/** Tests for [FhirEngineProvider] that need more than one Android context. */
class FhirEngineProviderAndroidTest {

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.cleanup()
  }

  @Test
  fun build_withAppAndActivityContext_shouldReturnSameFhirEngine() {
    val appContext = ApplicationProvider.getApplicationContext<Context>()
    FhirEngineProvider.init(FhirEngineConfiguration(), appContext)

    val engineAppContext = FhirEngineProvider.getInstance(appContext)
    val engineOtherContext =
      FhirEngineProvider.getInstance(InstrumentationRegistry.getInstrumentation().context)

    assertSame(engineAppContext, engineOtherContext)
  }
}
