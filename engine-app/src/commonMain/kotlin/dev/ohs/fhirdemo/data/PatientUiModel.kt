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

import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlinx.datetime.LocalDate

/** Plain-Kotlin projection of a FHIR Patient for UI use. Hides the wrapped FHIR types. */
data class PatientUiModel(
  val id: String?,
  val given: String,
  val family: String,
  val gender: AdministrativeGender?,
  val birthDate: LocalDate?,
  val phone: String,
  val email: String,
  val active: Boolean = true,
) {
  val displayName: String
    get() =
      listOf(given, family).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "(no name)" }

  companion object {
    val EMPTY =
      PatientUiModel(
        id = null,
        given = "",
        family = "",
        gender = null,
        birthDate = null,
        phone = "",
        email = "",
        active = true,
      )
  }
}
