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
package dev.ohs.fhirdemo

import android.app.Application
import android.content.Context
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhirdemo.data.DemoDataStore
import dev.ohs.fhirdemo.data.createDemoDataStore

class DemoApplication : Application() {
  private val dataStore by lazy { DemoDataStore(createDemoDataStore(applicationContext)) }

  companion object {
    fun fhirEngine(context: Context) = FhirEngineProvider.getInstance(context)

    fun dataStore(context: Context) = (context.applicationContext as DemoApplication).dataStore
  }
}
