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
package dev.ohs.fhir.engine.microbenchmark

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Decimal as FhirDecimal
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlinx.datetime.LocalDate

/**
 * Sample resources shared by every benchmark in this source set.
 *
 * Two patients on purpose. [minimalPatient] carries the fields almost every record has, and
 * [richPatient] carries the ones that make a record expensive — repeated names, identifiers,
 * telecom and addresses. Indexing and serialization both scale with that difference, so measuring
 * only one of them would hide it.
 */
object Fixtures {

  private const val LOINC = "http://loinc.org"
  private const val UCUM = "http://unitsofmeasure.org"

  val minimalPatient =
    Patient(
      id = "patient-minimal",
      active = FhirBoolean(value = true),
      name = listOf(HumanName(family = FhirString(value = "Smith"))),
      gender = Enumeration(value = AdministrativeGender.Male),
      birthDate = Date(value = FhirDate.Date(date = LocalDate(1980, 4, 17))),
    )

  val richPatient =
    Patient(
      id = "patient-rich",
      active = FhirBoolean(value = true),
      identifier =
        (1..4).map {
          Identifier(
            system = Uri(value = "urn:oid:1.2.3.$it"),
            value = FhirString(value = "id-$it"),
          )
        },
      name =
        listOf(
          HumanName(
            family = FhirString(value = "Okonkwo"),
            given = listOf(FhirString(value = "Adaeze"), FhirString(value = "Chidinma")),
          ),
          HumanName(
            family = FhirString(value = "Achebe"),
            given = listOf(FhirString(value = "Adaeze")),
          ),
        ),
      telecom = (1..3).map { ContactPoint(value = FhirString(value = "+25470000000$it")) },
      gender = Enumeration(value = AdministrativeGender.Female),
      birthDate = Date(value = FhirDate.Date(date = LocalDate(1975, 11, 2))),
      address =
        (1..2).map {
          Address(
            city = FhirString(value = "Nairobi"),
            postalCode = FhirString(value = "0010$it"),
            country = FhirString(value = "KE"),
          )
        },
      managingOrganization =
        Reference(reference = FhirString(value = "Organization/organization-1")),
    )

  val observation =
    Observation(
      id = "observation-1",
      status = Enumeration(value = Observation.ObservationStatus.Final),
      code =
        CodeableConcept(
          coding = listOf(Coding(system = Uri(value = LOINC), code = Code(value = "718-7"))),
        ),
      subject = Reference(reference = FhirString(value = "Patient/patient-rich")),
      value =
        Observation.Value.Quantity(
          Quantity(
            value = FhirDecimal(value = BigDecimal.fromInt(13)),
            unit = FhirString(value = "g/dL"),
            system = Uri(value = UCUM),
            code = Code(value = "g/dL"),
          ),
        ),
    )
}
