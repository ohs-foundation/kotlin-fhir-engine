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
import dev.ohs.fhir.NetworkConfiguration
import dev.ohs.fhir.ServerConfiguration
import dev.ohs.fhir.index.SearchParamDefinition
import dev.ohs.fhir.index.SearchParamType
import dev.ohs.fhir.sync.remote.HttpLogger

/**
 * Initializes [FhirEngineProvider] once and returns the [FhirEngine] instance.
 */
fun fhirEngine(platformContext: Any = Unit): FhirEngine {
  if (FhirEngineProvider.isNotInitialized()){
    FhirEngineProvider.init(
      FhirEngineConfiguration(
        serverConfiguration = ServerConfiguration(
          baseUrl = "https://hapi.fhir.org/baseR4/",
          networkConfiguration = NetworkConfiguration(uploadWithGzip = false),
          httpLogger = HttpLogger(level = HttpLogger.Level.BODY),
        ),
        customSearchParameters = listOf(
          SearchParamDefinition("name", SearchParamType.STRING, "Patient.name"),
          SearchParamDefinition("family", SearchParamType.STRING, "Patient.name.family"),
          SearchParamDefinition("given", SearchParamType.STRING, "Patient.name.given"),
        ),
      ),
      platformContext,
    )
  }

  return FhirEngineProvider.getInstance(platformContext)
}
