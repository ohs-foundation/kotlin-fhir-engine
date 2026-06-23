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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatientFormScreen(
  viewModel: PatientFormViewModel,
  onBack: () -> Unit,
) {
  val form by viewModel.form.collectAsStateWithLifecycle()
  val saving by viewModel.saving.collectAsStateWithLifecycle()
  var datePickerOpen by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (viewModel.isEdit) "Edit patient" else "New patient") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
        value = form.given,
        onValueChange = viewModel::setGiven,
        label = { Text("Given name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = form.family,
        onValueChange = viewModel::setFamily,
        label = { Text("Family name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      Text("Gender", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AdministrativeGender.entries.forEach { g ->
          val selected = form.gender == g
          AssistChip(
            onClick = { viewModel.setGender(if (selected) null else g) },
            label = { Text(g.getDisplay().orEmpty()) },
            colors =
              if (selected)
                AssistChipDefaults.assistChipColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              else AssistChipDefaults.assistChipColors(),
          )
        }
      }

      OutlinedTextField(
        value = form.birthDate?.toString().orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text("Birthdate") },
        trailingIcon = {
          IconButton(onClick = { datePickerOpen = true }) {
            Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date")
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )

      OutlinedTextField(
        value = form.phone,
        onValueChange = viewModel::setPhone,
        label = { Text("Phone") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = form.email,
        onValueChange = viewModel::setEmail,
        label = { Text("Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      Button(
        onClick = { viewModel.save(onBack) },
        enabled = viewModel.canSave() && !saving,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      ) {
        if (saving) {
          CircularProgressIndicator(
            modifier = Modifier.padding(end = 12.dp),
            strokeWidth = 2.dp,
          )
        }
        Text(if (viewModel.isEdit) "Save changes" else "Create patient")
      }

      if (!viewModel.canSave()) {
        Text(
          text = "At least a given or family name is required.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }

  if (datePickerOpen) {
    BirthdatePickerDialog(
      initial = form.birthDate,
      onDismiss = { datePickerOpen = false },
      onConfirm = {
        viewModel.setBirthDate(it)
        datePickerOpen = false
      },
    )
  }
}

@Composable
private fun BirthdatePickerDialog(
  initial: LocalDate?,
  onDismiss: () -> Unit,
  onConfirm: (LocalDate?) -> Unit,
) {
  val initialMillis =
    initial?.let { date ->
      val instant = date.atStartOfDayIn(TimeZone.UTC)
      instant.toEpochMilliseconds()
    }
  val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(
        onClick = {
          val millis = state.selectedDateMillis
          val date =
            millis?.let { ms ->
              Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
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

