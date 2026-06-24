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

import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString

internal fun Patient.toUi(): PatientUiModel {
  val name = name.firstOrNull()
  val phone = telecom.firstOrNull { it.system?.value == ContactPoint.ContactPointSystem.Phone }
  val email = telecom.firstOrNull { it.system?.value == ContactPoint.ContactPointSystem.Email }
  return PatientUiModel(
    id = id,
    given = name?.given?.firstOrNull()?.value.orEmpty(),
    family = name?.family?.value.orEmpty(),
    gender = gender?.value,
    birthDate = (birthDate?.value as? FhirDate.Date)?.date,
    phone = phone?.value?.value.orEmpty(),
    email = email?.value?.value.orEmpty(),
    active = active?.value ?: false,
  )
}

internal fun PatientUiModel.toFhir(): Patient =
  Patient(
    id = id,
    active = FhirBoolean(value = active),
    name =
      if (given.isBlank() && family.isBlank()) {
        emptyList()
      } else {
        listOf(
          HumanName(
            family = family.takeIf { it.isNotBlank() }?.let { FhirString(value = it) },
            given = if (given.isBlank()) emptyList() else listOf(FhirString(value = given)),
          ),
        )
      },
    gender = gender?.let { Enumeration(value = it) },
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
