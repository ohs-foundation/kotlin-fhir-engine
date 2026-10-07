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

/** Fails a trial whose query no longer selects the slice it was designed for. */
internal fun assertSelectivity(matched: Int, rows: Int, expectedFraction: Double, arm: String) {
  val expected = rows * expectedFraction
  // Allow a fifth or one row, whichever is larger.
  val tolerance = maxOf(1.0, expected * 0.2)
  check(matched > 0 && matched >= expected - tolerance && matched <= expected + tolerance) {
    "arm '$arm' at $rows rows matched $matched, expected about ${expected.toInt()}. The query is " +
      "no longer selecting the slice this benchmark assumes, so its timings are not comparable."
  }
}
