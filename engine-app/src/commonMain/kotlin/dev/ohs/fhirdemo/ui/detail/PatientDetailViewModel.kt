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
package dev.ohs.fhirdemo.ui.detail

import dev.ohs.fhirdemo.data.PatientRepository
import dev.ohs.fhirdemo.data.PatientUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PatientDetailViewModel(
  private val patientId: String,
  private val repository: PatientRepository,
  private val scope: CoroutineScope,
) {
  private val _patient = MutableStateFlow<PatientUiModel?>(null)
  val patient: StateFlow<PatientUiModel?> = _patient.asStateFlow()

  init {
    scope.launch { _patient.value = repository.get(patientId) }
  }

  fun delete(onComplete: () -> Unit) {
    scope.launch {
      repository.delete(patientId)
      onComplete()
    }
  }
}
