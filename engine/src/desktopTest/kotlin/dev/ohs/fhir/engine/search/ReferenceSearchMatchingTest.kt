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
package dev.ohs.fhir.engine.search

import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.search.filter.ReferenceParamFilterCriterion
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Task
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Executes a reference search with a long list of values against a real database. `SearchTest` only
 * checks the SQL such a list compiles to, not that SQLite accepts it and returns the right rows.
 */
class ReferenceSearchMatchingTest {

  @AfterTest
  fun tearDown() {
    FhirEngineProvider.resetForTesting()
  }

  @Test
  fun searchTasksForManyPatientsReturnCorrectly() = runTest {
    FhirEngineProvider.init(
      FhirEngineConfiguration(testMode = true, storageDirectory = testStorageDirectory()),
    )
    val fhirEngine = FhirEngineProvider.getInstance()
    val patients = (0 until 990).map { Patient(id = "task-patient-index-$it") }
    fhirEngine.create(*patients.toTypedArray())
    val tasks =
      patients.mapIndexed { index, patient ->
        Task(
          id = "patient-$index-task",
          status = Enumeration(value = Task.TaskStatus.Requested),
          intent = Enumeration(value = Task.TaskIntent.Order),
          `for` = Reference(reference = FhirString(value = "Patient/${patient.id}")),
        )
      }
    fhirEngine.create(*tasks.toTypedArray())

    // 980 values stay below the 999 bound parameter limit of older SQLite builds, as in
    // android-fhir.
    val patientsSearchIdList =
      patients.take(980).map<Patient, ReferenceParamFilterCriterion.() -> Unit> {
        { value = "Patient/${it.id}" }
      }
    val searchResults =
      fhirEngine.search<Task> {
        filter(ReferenceClientParam("subject"), *patientsSearchIdList.toTypedArray())
      }

    assertEquals(
      (0 until 980).map { "patient-$it-task" }.toSet(),
      searchResults.map { it.resource.id }.toSet(),
    )
    assertEquals(980, searchResults.size)
  }
}
