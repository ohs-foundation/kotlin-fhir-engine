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
package dev.ohs.fhir.engine.app.data

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.delete
import dev.ohs.fhir.engine.get
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.model.r4.Patient

class PatientRepository(private val engine: FhirEngine) {

  suspend fun list(query: String = ""): List<PatientUiModel> {
    val results =
      engine.search<Patient> {
        if (query.isNotBlank()) {
          filter(StringClientParam("name"), { value = query })
        }
      }
    return results.map { it.resource.toUi() }
  }

  suspend fun get(id: String): PatientUiModel = engine.get<Patient>(id).toUi()

  suspend fun create(model: PatientUiModel): String {
    val patient = model.copy(id = null).toFhir()
    return engine.create(patient).first()
  }

  /**
   * Creates a patient preserving the caller-supplied [PatientUiModel.id] (used by the CRUD demo).
   */
  suspend fun createWithId(model: PatientUiModel): String {
    requireNotNull(model.id) { "createWithId requires a non-null id" }
    return engine.create(model.toFhir()).first()
  }

  suspend fun getOrNull(id: String): PatientUiModel? = runCatching { get(id) }.getOrNull()

  suspend fun update(model: PatientUiModel) {
    requireNotNull(model.id) { "Cannot update a patient with no id" }
    engine.update(model.toFhir())
  }

  suspend fun delete(id: String) {
    engine.delete<Patient>(id)
  }
}
