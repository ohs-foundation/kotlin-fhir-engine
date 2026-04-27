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

package com.example.sdckmpdemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.search.search
import com.google.fhir.model.r4.Patient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
  private val fhirEngine = FhirEngineProvider.getInstance()

  private val _patients = MutableStateFlow<List<Patient>>(emptyList())
  val patients: StateFlow<List<Patient>> = _patients.asStateFlow()

  init {
    viewModelScope.launch {
      refreshPatients()
    }
  }

  fun refreshPatients() {
    viewModelScope.launch {
      val results = fhirEngine.search<Patient> {}
      _patients.value = results.map { it.resource }
    }
  }

  fun createPatient(patient: Patient) {
    viewModelScope.launch {
      fhirEngine.create(patient)
      refreshPatients()
    }
  }
}
