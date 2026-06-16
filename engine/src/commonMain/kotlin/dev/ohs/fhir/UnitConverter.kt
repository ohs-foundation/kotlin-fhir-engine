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

import dev.ohs.fhir.fhirpath.toEqualCanonicalized
import dev.ohs.fhir.fhirpath.toEquivalentCanonicalized
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * UCUM canonicalization for [UcumValue], delegating to kotlin-fhir-path's `FhirPathQuantity`
 * extensions.
 *
 * For details of UCUM, see http://unitsofmeasure.org/. For using UCUM with FHIR, see
 * https://www.hl7.org/fhir/ucum.html.
 */
internal data class UcumValue(val code: String, val value: BigDecimal)

/**
 * Returns the canonical form of this value using FHIRPath's strict "equal" canonicalization
 * (handles UCUM prefixes and definite-duration units like `wk`/`d`/`h`). Returns the original value
 * if no canonical form is known.
 */
internal fun UcumValue.toEqualCanonical(): UcumValue =
  toFhirPathQuantity().toEqualCanonicalized().toUcumValue(fallback = this)

/**
 * Returns the canonical form of this value using FHIRPath's loose "equivalent" canonicalization
 * (additionally handles calendar units like `year`/`month`). Returns the original value if no
 * canonical form is known.
 */
internal fun UcumValue.toEquivalentCanonical(): UcumValue =
  toFhirPathQuantity().toEquivalentCanonicalized().toUcumValue(fallback = this)

private fun UcumValue.toFhirPathQuantity(): FhirPathQuantity =
  FhirPathQuantity(value = value, unit = "'$code'")

private fun FhirPathQuantity.toUcumValue(fallback: UcumValue): UcumValue =
  UcumValue(
    code = unit?.trim('\'') ?: fallback.code,
    value = value ?: fallback.value,
  )
