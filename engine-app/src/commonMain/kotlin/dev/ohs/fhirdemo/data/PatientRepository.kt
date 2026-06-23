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
import dev.ohs.fhir.delete
import dev.ohs.fhir.get
import dev.ohs.fhir.search.StringClientParam
import dev.ohs.fhir.search.Search
import dev.ohs.fhir.search.search
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.String as FhirString

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

  /** Creates a patient preserving the caller-supplied [PatientUiModel.id] (used by the CRUD demo). */
  suspend fun createWithId(model: PatientUiModel): String {
    requireNotNull(model.id) { "createWithId requires a non-null id" }
    return engine.create(model.toFhir()).first()
  }

  suspend fun getOrNull(id: String): PatientUiModel? =
    runCatching { get(id) }.getOrNull()

  suspend fun update(model: PatientUiModel) {
    requireNotNull(model.id) { "Cannot update a patient with no id" }
    engine.update(model.toFhir())
  }

  suspend fun delete(id: String) {
    engine.delete<Patient>(id)
  }
}

private fun Patient.toUi(): PatientUiModel {
  val name = name.firstOrNull()
  val phone = telecom.firstOrNull { it.system?.value == ContactPoint.ContactPointSystem.Phone }
  val email = telecom.firstOrNull { it.system?.value == ContactPoint.ContactPointSystem.Email }
  return PatientUiModel(
    id = id,
    given = name?.given?.firstOrNull()?.value.orEmpty(),
    family = name?.family?.value.orEmpty(),
    gender = gender?.value?.toUi(),
    birthDate = (birthDate?.value as? FhirDate.Date)?.date,
    phone = phone?.value?.value.orEmpty(),
    email = email?.value?.value.orEmpty(),
    active = active?.value ?: false,
  )
}

private fun PatientUiModel.toFhir(): Patient =
  Patient(
    id = id,
    active = FhirBoolean(value = active),
    name =
      if (given.isBlank() && family.isBlank()) emptyList()
      else
        listOf(
          HumanName(
            family = family.takeIf { it.isNotBlank() }?.let { FhirString(value = it) },
            given = if (given.isBlank()) emptyList() else listOf(FhirString(value = given)),
          ),
        ),
    gender = gender?.let { Enumeration(value = it.toFhir()) },
    birthDate = birthDate?.let { Date(value = FhirDate.Date(date = it)) },
    telecom =
      buildList {
        if (phone.isNotBlank()) add(contactPoint(ContactPoint.ContactPointSystem.Phone, phone))
        if (email.isNotBlank()) add(contactPoint(ContactPoint.ContactPointSystem.Email, email))
      },
  )

private fun contactPoint(system: ContactPoint.ContactPointSystem, value: String): ContactPoint =
  ContactPoint(
    system = Enumeration(value = system),
    value = FhirString(value = value),
  )

private fun AdministrativeGender.toUi(): Gender =
  when (this) {
    AdministrativeGender.Male -> Gender.MALE
    AdministrativeGender.Female -> Gender.FEMALE
    AdministrativeGender.Other -> Gender.OTHER
    AdministrativeGender.Unknown -> Gender.UNKNOWN
  }

private fun Gender.toFhir(): AdministrativeGender =
  when (this) {
    Gender.MALE -> AdministrativeGender.Male
    Gender.FEMALE -> AdministrativeGender.Female
    Gender.OTHER -> AdministrativeGender.Other
    Gender.UNKNOWN -> AdministrativeGender.Unknown
  }
