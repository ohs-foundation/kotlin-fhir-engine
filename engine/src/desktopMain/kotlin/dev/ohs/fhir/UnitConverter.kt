/*
 * Copyright 2025-2026 Google LLC
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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import java.math.MathContext
import org.fhir.ucum.Decimal
import org.fhir.ucum.Pair
import org.fhir.ucum.UcumEssenceService
import org.fhir.ucum.UcumException
import java.math.BigDecimal as JavaBigDecimal

internal actual object UnitConverter {
  private val ucumService by lazy {
    UcumEssenceService(this::class.java.getResourceAsStream("/ucum-essence.xml"))
  }

  private fun getCanonicalForm(value: UcumValue): UcumValue {
    try {
      val pair =
        ucumService.getCanonicalForm(Pair(Decimal(value.value.toPlainString()), value.code))
      return UcumValue(pair.code, pair.value.asDecimal().toKmp(value.value.precision.toInt()))
    } catch (e: UcumException) {
      throw ConverterException("UCUM conversion failed", e)
    } catch (e: NullPointerException) {
      // See https://github.com/google/android-fhir/issues/869 for why NPE needs to be caught
      throw ConverterException("Missing numerical value in the canonical UCUM value", e)
    }
  }

  actual fun getCanonicalFormOrOriginal(value: UcumValue): UcumValue =
    try {
      getCanonicalForm(value)
    } catch (e: ConverterException) {
      val pair = Pair(Decimal(value.value.toPlainString()), value.code)
      UcumValue(pair.code, pair.value.asDecimal().toKmp(value.value.precision.toInt()))
    }

  // org.fhir.ucum.Decimal.asDecimal() returns a String. Round through java.math.BigDecimal at the
  // input value's precision (matching the JVM common-module impl) before converting to ionspin's
  // multiplatform BigDecimal.
  private fun String.toKmp(precision: Int): BigDecimal =
    BigDecimal.parseString(JavaBigDecimal(this).round(MathContext(precision)).toPlainString())
}
