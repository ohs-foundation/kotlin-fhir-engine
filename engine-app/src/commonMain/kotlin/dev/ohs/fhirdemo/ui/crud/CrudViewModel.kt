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

package dev.ohs.fhirdemo.ui.crud

import dev.ohs.fhirdemo.data.Gender
import dev.ohs.fhirdemo.data.PatientRepository
import dev.ohs.fhirdemo.data.PatientUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

enum class CrudTab {
  CREATE,
  READ,
  UPDATE,
  DELETE,
}

data class CrudFormState(
  val id: String = "",
  val firstName: String = "",
  val lastName: String = "",
  val birthDate: String = "",
  val gender: Gender? = null,
  val active: Boolean = false,
  val editable: Boolean = true,
)

/**
 * Functional CRUD demo backed by the engine, mirroring the demo's `CrudOperationFragment`. Each tab
 * maps to one operation; Create generates a fresh logical id which subsequent Read/Update/Delete
 * operate on.
 */
class CrudViewModel(
  private val repository: PatientRepository,
  private val scope: CoroutineScope,
) {
  private val _tab = MutableStateFlow(CrudTab.CREATE)
  val tab: StateFlow<CrudTab> = _tab.asStateFlow()

  private val _form = MutableStateFlow(CrudFormState(id = newId(), editable = true))
  val form: StateFlow<CrudFormState> = _form.asStateFlow()

  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  private var currentPatientId: String? = null

  fun setFirstName(v: String) = update { it.copy(firstName = v) }

  fun setLastName(v: String) = update { it.copy(lastName = v) }

  fun setBirthDate(v: String) = update { it.copy(birthDate = v) }

  fun setGender(v: Gender?) = update { it.copy(gender = v) }

  fun setActive(v: Boolean) = update { it.copy(active = v) }

  fun selectTab(tab: CrudTab) {
    _tab.value = tab
    when (tab) {
      CrudTab.CREATE -> _form.value = CrudFormState(id = newId(), editable = true)
      CrudTab.READ -> _form.value = CrudFormState(id = currentPatientId.orEmpty(), editable = false)
      CrudTab.UPDATE -> loadCurrent(editable = true)
      CrudTab.DELETE -> loadCurrent(editable = false)
    }
  }

  fun submit() {
    when (_tab.value) {
      CrudTab.CREATE -> create()
      CrudTab.READ -> read()
      CrudTab.UPDATE -> updatePatient()
      CrudTab.DELETE -> deletePatient()
    }
  }

  private fun create() {
    val form = _form.value
    if (form.firstName.isBlank()) {
      notify("First name is required.")
      return
    }
    val birthDate =
      parseBirthDate(form.birthDate).getOrElse {
        notify("Please enter a valid birth date (yyyy-MM-dd).")
        return
      }
    scope.launch {
      val model =
        PatientUiModel(
          id = form.id,
          given = form.firstName.trim(),
          family = form.lastName.trim(),
          gender = form.gender,
          birthDate = birthDate,
          phone = "",
          email = "",
          active = form.active,
        )
      repository.createWithId(model)
      currentPatientId = form.id
      notify("Patient is saved.")
      // Reset for the next create.
      _form.value = CrudFormState(id = newId(), editable = true)
    }
  }

  private fun read() {
    val id = currentPatientId
    if (id.isNullOrEmpty()) {
      notify("Please create a patient first.")
      return
    }
    scope.launch {
      val model = repository.getOrNull(id)
      if (model == null) {
        notify("Patient not found.")
        return@launch
      }
      _form.value = model.toFormState(editable = false)
    }
  }

  private fun updatePatient() {
    val id = currentPatientId
    if (id.isNullOrEmpty()) {
      notify("Please create a patient first.")
      return
    }
    val form = _form.value
    if (form.firstName.isBlank()) {
      notify("First name is required.")
      return
    }
    val birthDate =
      parseBirthDate(form.birthDate).getOrElse {
        notify("Please enter a valid birth date (yyyy-MM-dd).")
        return
      }
    scope.launch {
      val model =
        PatientUiModel(
          id = id,
          given = form.firstName.trim(),
          family = form.lastName.trim(),
          gender = form.gender,
          birthDate = birthDate,
          phone = "",
          email = "",
          active = form.active,
        )
      repository.update(model)
      notify("Patient is updated.")
    }
  }

  private fun deletePatient() {
    val id = currentPatientId
    if (id.isNullOrEmpty()) {
      notify("Please create a patient first.")
      return
    }
    scope.launch {
      repository.delete(id)
      currentPatientId = null
      _form.value = CrudFormState(editable = false)
      notify("Patient is deleted.")
    }
  }

  private fun loadCurrent(editable: Boolean) {
    val id = currentPatientId
    if (id.isNullOrEmpty()) {
      _form.value = CrudFormState(editable = editable)
      return
    }
    scope.launch {
      val model = repository.getOrNull(id)
      _form.value = model?.toFormState(editable = editable) ?: CrudFormState(id = id, editable = editable)
    }
  }

  /**
   * Parses a `yyyy-MM-dd` birth date. An empty string is valid and yields `success(null)`; a
   * non-empty but unparseable string yields `failure` so callers can abort and notify.
   */
  private fun parseBirthDate(value: String): Result<LocalDate?> {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return Result.success(null)
    return runCatching { LocalDate.parse(trimmed) }
  }

  private fun update(block: (CrudFormState) -> CrudFormState) {
    _form.value = block(_form.value)
  }

  private fun notify(message: String) {
    _messages.trySend(message)
  }

  private fun PatientUiModel.toFormState(editable: Boolean) =
    CrudFormState(
      id = id.orEmpty(),
      firstName = given,
      lastName = family,
      birthDate = birthDate?.toString().orEmpty(),
      gender = gender,
      active = active,
      editable = editable,
    )

  private fun newId(): String = Uuid.random().toString()
}
