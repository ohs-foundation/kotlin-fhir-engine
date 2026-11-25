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
package dev.ohs.fhir.codelab

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.search.Order
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.engine.sync.CurrentSyncJobStatus
import dev.ohs.fhir.model.r4.Patient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PatientListViewModel(
  private val fhirEngine: FhirEngine,
  private val syncLauncher: SyncLauncher,
  private val scope: CoroutineScope,
) {
  private val _pollState = MutableSharedFlow<CurrentSyncJobStatus>()
  val pollState: SharedFlow<CurrentSyncJobStatus> = _pollState

  private val _patients = MutableStateFlow<List<Patient>>(emptyList())
  val patients: StateFlow<List<Patient>> = _patients

  /** The name typed in the search field. */
  private var nameQuery = ""

  private var loadJob: Job? = null

  init {
    refreshPatients()
  }

  /**
   * Loads the patients from the local database, sorted by family name. Cancels the previous load,
   * so an older search can't replace the result of a newer one.
   */
  fun refreshPatients() {
    loadJob?.cancel()
    loadJob =
      scope.launch {
        _patients.value =
          fhirEngine
            .search<Patient> {
              sort(StringClientParam("family"), Order.ASCENDING)
              // 7 Replace with code from the codelab to filter the patients by name.
            }
            .map { it.resource }
      }
  }

  fun triggerOneTimeSync() {
    // 5.3 Replace with code from the codelab to start the sync.
  }

  // 6.1 Replace with code from the codelab to search for patients by city.

  // 6.2 Replace with code from the codelab to change the city of a patient.

  /** Swaps the city of patients in Wakefield and Taunton, then syncs the changes. */
  fun triggerUpdate() {
    // 6.3 Replace with code from the codelab to update the patients and sync.
  }

  /** Shows only the patients whose name contains [query], or all patients if it is empty. */
  fun searchPatientsByName(query: String) {
    nameQuery = query
    refreshPatients()
  }
}
