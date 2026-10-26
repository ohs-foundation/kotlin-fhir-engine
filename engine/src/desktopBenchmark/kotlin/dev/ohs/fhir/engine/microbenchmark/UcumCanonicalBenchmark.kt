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
import dev.ohs.fhir.engine.UcumValue
import dev.ohs.fhir.engine.toEqualCanonical
import dev.ohs.fhir.engine.toEquivalentCanonical
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/** Measures rewriting a quantity into its canonical unit, done for every quantity index. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class UcumCanonicalBenchmark {

  private val convertible = UcumValue(code = "kg", value = BigDecimal.fromInt(72))
  private val passThrough = UcumValue(code = "g/dL", value = BigDecimal.fromInt(13))
  private val calendar = UcumValue(code = "wk", value = BigDecimal.fromInt(6))

  @Setup
  fun setUp() {
    check(convertible.toEqualCanonical().code != convertible.code) {
      "'${convertible.code}' is no longer converted, so convertibleUnit measures a pass-through"
    }
    check(passThrough.toEqualCanonical().code == passThrough.code) {
      "'${passThrough.code}' is now converted, so passThroughUnit measures a conversion"
    }
  }

  /** A unit with a canonical form: the code and the value are both rewritten. */
  @Benchmark
  fun convertibleUnit(blackhole: Blackhole) = blackhole.consume(convertible.toEqualCanonical())

  /** A unit with no canonical form, the common case. */
  @Benchmark
  fun passThroughUnit(blackhole: Blackhole) = blackhole.consume(passThrough.toEqualCanonical())

  /** The looser canonicalization, which also resolves calendar units like `wk`. */
  @Benchmark
  fun calendarUnit(blackhole: Blackhole) = blackhole.consume(calendar.toEquivalentCanonical())
}
