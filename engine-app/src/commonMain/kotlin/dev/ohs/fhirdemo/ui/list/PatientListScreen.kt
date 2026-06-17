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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.fhirdemo.data.PatientUiModel

@Composable
fun PatientListScreen(
  viewModel: PatientListViewModel,
  onAddClick: () -> Unit,
  onPatientClick: (id: String) -> Unit,
  onBack: () -> Unit,
) {
  val query by viewModel.query.collectAsStateWithLifecycle()
  val patients by viewModel.patients.collectAsStateWithLifecycle()
  val loading by viewModel.loading.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Patients") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = onAddClick) {
        Icon(Icons.Default.Add, contentDescription = "Add patient")
      }
    },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      OutlinedTextField(
        value = query,
        onValueChange = viewModel::setQuery,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        placeholder = { Text("Search by name") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
      )

      if (loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      if (patients.isEmpty() && !loading) {
        EmptyState(query = query)
      } else {
        LazyColumn(
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(patients, key = { it.id ?: it.displayName }) { patient ->
            PatientRow(patient = patient, onClick = { patient.id?.let(onPatientClick) })
          }
        }
      }
    }
  }
}

@Composable
private fun PatientRow(patient: PatientUiModel, onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Default.Person,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
        Text(text = patient.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val sub =
          listOfNotNull(
              patient.gender?.display,
              patient.birthDate?.toString(),
            )
            .joinToString(" • ")
        if (sub.isNotEmpty()) {
          Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun EmptyState(query: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
      Text(
        text = if (query.isBlank()) "No patients yet." else "No matches for \"$query\".",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
      )
      if (query.isBlank()) {
        Text(
          text = "Tap + to add one.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
