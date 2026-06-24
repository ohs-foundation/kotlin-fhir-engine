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
package dev.ohs.fhirdemo.data

import dev.ohs.fhir.FhirEngine
import dev.ohs.fhir.FhirEngineConfiguration
import dev.ohs.fhir.FhirEngineProvider
import dev.ohs.fhir.index.SearchParamDefinition
import dev.ohs.fhir.index.SearchParamType

/**
 * Initializes [FhirEngineProvider] exactly once. Backed by [lazy], which defaults to
 * [LazyThreadSafetyMode.SYNCHRONIZED] and is safe across all Kotlin Multiplatform targets.
 */
private val engineInitializer: Unit by lazy {
  FhirEngineProvider.init(
    FhirEngineConfiguration(
      customSearchParameters =
        listOf(
          SearchParamDefinition("name", SearchParamType.STRING, "Patient.name"),
          SearchParamDefinition("family", SearchParamType.STRING, "Patient.name.family"),
          SearchParamDefinition("given", SearchParamType.STRING, "Patient.name.given"),
        ),
    ),
  )
}

fun fhirEngine(platformContext: Any = Unit): FhirEngine {
  engineInitializer
  return FhirEngineProvider.getInstance(platformContext)
}
