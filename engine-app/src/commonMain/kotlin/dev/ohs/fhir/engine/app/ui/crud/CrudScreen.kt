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
package dev.ohs.fhir.engine.app.ui.crud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

@Composable
fun CrudScreen(
  viewModel: CrudViewModel,
  onBack: () -> Unit,
) {
  val tab by viewModel.tab.collectAsStateWithLifecycle()
  val form by viewModel.form.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  var datePickerOpen by remember { mutableStateOf(false) }

  LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("CRUD operations") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      PrimaryTabRow(selectedTabIndex = tab.ordinal) {
        CrudTab.entries.forEach { t ->
          Tab(
            selected = tab == t,
            onClick = { viewModel.selectTab(t) },
            text = { Text(t.label) },
          )
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedTextField(
          value = form.id,
          onValueChange = {},
          label = { Text("Id *") },
          readOnly = true,
          enabled = false,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = form.firstName,
          onValueChange = viewModel::setFirstName,
          label = { Text("First Name *") },
          singleLine = true,
          enabled = form.editable,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = form.lastName,
          onValueChange = viewModel::setLastName,
          label = { Text("Last Name") },
          singleLine = true,
          enabled = form.editable,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = form.birthDate,
          onValueChange = {},
          readOnly = true,
          enabled = form.editable,
          label = { Text("Birth Date") },
          trailingIcon = {
            IconButton(onClick = { datePickerOpen = true }, enabled = form.editable) {
              Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date")
            }
          },
          modifier = Modifier.fillMaxWidth(),
        )

        Text("Gender", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
          GenderOption(
            AdministrativeGender.Male,
            "Male",
            form.gender,
            form.editable,
            viewModel::setGender,
          )
          GenderOption(
            AdministrativeGender.Female,
            "Female",
            form.gender,
            form.editable,
            viewModel::setGender,
          )
          GenderOption(
            AdministrativeGender.Other,
            "Other",
            form.gender,
            form.editable,
            viewModel::setGender,
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = form.active,
            onCheckedChange = viewModel::setActive,
            enabled = form.editable,
          )
          Text("Is Active")
        }

        Button(
          onClick = viewModel::submit,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
          Text(tab.label, fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }

  if (datePickerOpen) {
    BirthDatePickerDialog(
      initial = form.birthDate,
      onDismiss = { datePickerOpen = false },
      onConfirm = { date ->
        viewModel.setBirthDate(date?.toString().orEmpty())
        datePickerOpen = false
      },
    )
  }
}

@Composable
private fun BirthDatePickerDialog(
  initial: String,
  onDismiss: () -> Unit,
  onConfirm: (LocalDate?) -> Unit,
) {
  val initialMillis =
    runCatching { LocalDate.parse(initial) }
      .getOrNull()
      ?.atStartOfDayIn(TimeZone.UTC)
      ?.toEpochMilliseconds()
  val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(
        onClick = {
          val date =
            state.selectedDateMillis?.let {
              Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
            }
          onConfirm(date)
        },
      ) {
        Text("OK")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  ) {
    DatePicker(state = state)
  }
}

@Composable
private fun GenderOption(
  value: AdministrativeGender,
  label: String,
  selected: AdministrativeGender?,
  enabled: Boolean,
  onSelect: (AdministrativeGender) -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.padding(end = 8.dp)
        .selectable(selected = selected == value, enabled = enabled, onClick = { onSelect(value) }),
  ) {
    RadioButton(
      selected = selected == value,
      onClick = { onSelect(value) },
      enabled = enabled,
    )
    Text(label)
  }
}

private val CrudTab.label: String
  get() =
    when (this) {
      CrudTab.CREATE -> "Create"
      CrudTab.READ -> "Read"
      CrudTab.UPDATE -> "Update"
      CrudTab.DELETE -> "Delete"
    }
