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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.fhir.engine.sync.CurrentSyncJobStatus
import dev.ohs.fhir.model.r4.Patient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientListScreen(viewModel: PatientListViewModel) {
  val patients by viewModel.patients.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  var query by remember { mutableStateOf("") }

  LaunchedEffect(viewModel) {
    viewModel.pollState.collect { handleSyncJobStatus(it, viewModel, snackbarHostState) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("FHIR Engine Codelab") },
        actions = {
          TextButton(onClick = viewModel::triggerOneTimeSync) { Text("Sync") }
          TextButton(onClick = viewModel::triggerUpdate) { Text("Update") }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      OutlinedTextField(
        value = query,
        onValueChange = {
          query = it
          viewModel.searchPatientsByName(it)
        },
        label = { Text("Search by name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      )
      LazyColumn {
        items(patients, key = { it.id ?: it.hashCode().toString() }) { patient ->
          PatientItem(patient)
          HorizontalDivider()
        }
      }
    }
  }
}

private suspend fun handleSyncJobStatus(
  syncJobStatus: CurrentSyncJobStatus,
  viewModel: PatientListViewModel,
  snackbarHostState: SnackbarHostState,
) {
  // 5.4 Replace with code from the codelab to show a message when the sync completes.
}

@Composable
private fun PatientItem(patient: Patient) {
  val name =
    patient.name.firstOrNull()?.let { humanName ->
      (humanName.given.mapNotNull { it.value } + listOfNotNull(humanName.family?.value))
        .joinToString(" ")
    }
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text(name.orEmpty(), style = MaterialTheme.typography.titleMedium)
    Text(
      listOfNotNull(patient.gender?.value?.display, patient.address.singleOrNull()?.city?.value)
        .joinToString(" · "),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}
