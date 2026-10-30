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
package dev.ohs.fhir.engine.benchmark.workloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchWorkloadsTest {

  /** Queries ported from android-fhir's `SearchApiViewModel`. */
  @Test
  fun `covers the search paths ported from android-fhir`() {
    val ids = SearchWorkloads.all().map { it.id }

    for (id in
      listOf(
        "search.patient_given_or_birthdate",
        "search.patient_given_disjunct_values",
        "search.encounter_by_last_updated",
        "search.risk_assessment_by_probability",
        "search.risk_assessment_probability_or_status",
      )) {
      assertTrue(id in ids, "Missing $id. Catalogue holds: $ids")
    }
  }

  @Test
  fun `every search workload is in the search group`() {
    assertTrue(SearchWorkloads.all().all { it.group == "search" })
  }

  /** An id keys both the trace section and the report row. */
  @Test
  fun `workload ids are unique across the whole catalogue`() {
    val ids = Workloads.all().map { it.id }

    assertEquals(
      ids.size,
      ids.toSet().size,
      "Duplicate ids: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }}",
    )
  }
}
