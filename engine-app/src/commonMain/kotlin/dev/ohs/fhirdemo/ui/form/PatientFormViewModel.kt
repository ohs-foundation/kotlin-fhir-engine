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

package dev.ohs.fhirdemo.ui.form

import dev.ohs.fhirdemo.data.Gender
import dev.ohs.fhirdemo.data.PatientRepository
import dev.ohs.fhirdemo.data.PatientUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

class PatientFormViewModel(
  private val patientId: String?,
  private val repository: PatientRepository,
  private val scope: CoroutineScope,
) {
  val isEdit: Boolean = patientId != null

  private val _form = MutableStateFlow(PatientUiModel.EMPTY)
  val form: StateFlow<PatientUiModel> = _form.asStateFlow()

  private val _saving = MutableStateFlow(false)
  val saving: StateFlow<Boolean> = _saving.asStateFlow()

  init {
    if (patientId != null) {
      scope.launch { _form.value = repository.get(patientId) }
    }
  }

  fun setGiven(v: String) = update { it.copy(given = v) }

  fun setFamily(v: String) = update { it.copy(family = v) }

  fun setGender(v: Gender?) = update { it.copy(gender = v) }

  fun setBirthDate(v: LocalDate?) = update { it.copy(birthDate = v) }

  fun setPhone(v: String) = update { it.copy(phone = v) }

  fun setEmail(v: String) = update { it.copy(email = v) }

  fun canSave(): Boolean = _form.value.given.isNotBlank() || _form.value.family.isNotBlank()

  fun save(onSaved: () -> Unit) {
    if (!canSave()) return
    scope.launch {
      _saving.value = true
      try {
        if (isEdit) repository.update(_form.value) else repository.create(_form.value)
        onSaved()
      } finally {
        _saving.value = false
      }
    }
  }

  private fun update(block: (PatientUiModel) -> PatientUiModel) {
    _form.value = block(_form.value)
  }
}
