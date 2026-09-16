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
package dev.ohs.fhir.engine.index.entities

import androidx.room3.ColumnInfo

/**
 * An index record for a string value in a resource.
 *
 * See https://hl7.org/FHIR/search.html#string.
 */
internal data class StringIndex(
  /** The name of the string index, e.g. "given". */
  val name: String,
  /** The path of the string index, e.g. "Patient.name.given". */
  val path: String,
  /**
   * The value of the string index, e.g. "Tom".
   *
   * Stored NOCASE so the index over it is NOCASE too. FHIR string search is case-insensitive and
   * compiles to a `LIKE` comparison; SQLite only uses an index whose collation matches the
   * comparison's, so a BINARY column here left every prefix search narrowing on `(resourceType,
   * index_name)` and examining the rest. Measured at 38x on a 50,000-row table by
   * `StringIndexCollationBenchmark`.
   *
   * This is only half the fix — see `StringParamFilterCriterion`, which binds the pattern whole.
   * Either half alone leaves the optimisation off.
   */
  @ColumnInfo(collate = ColumnInfo.NOCASE) val value: String,
)
