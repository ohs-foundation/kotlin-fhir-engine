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

package dev.ohs.fhirdemo.ui.list

import dev.ohs.fhirdemo.data.PatientRepository
import dev.ohs.fhirdemo.data.PatientUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PatientListViewModel(
  private val repository: PatientRepository,
  private val scope: CoroutineScope,
) {
  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()

  private val _patients = MutableStateFlow<List<PatientUiModel>>(emptyList())
  val patients: StateFlow<List<PatientUiModel>> = _patients.asStateFlow()

  private val _loading = MutableStateFlow(false)
  val loading: StateFlow<Boolean> = _loading.asStateFlow()

  init {
    reload()
  }

  fun setQuery(value: String) {
    _query.value = value
    reload()
  }

  fun reload() {
    scope.launch {
      _loading.value = true
      try {
        _patients.value = repository.list(_query.value)
      } finally {
        _loading.value = false
      }
    }
  }
}
