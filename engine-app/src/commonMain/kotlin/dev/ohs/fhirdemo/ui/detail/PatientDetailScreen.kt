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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.fhirdemo.data.PatientUiModel

@Composable
fun PatientDetailScreen(
  viewModel: PatientDetailViewModel,
  onBack: () -> Unit,
  onEdit: (id: String) -> Unit,
) {
  val patient by viewModel.patient.collectAsStateWithLifecycle()
  var confirmingDelete by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(patient?.displayName ?: "Patient") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    val p = patient
    if (p == null) {
      Loading(Modifier.padding(padding))
      return@Scaffold
    }

    Column(
      modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      DetailField("Given name", p.given)
      DetailField("Family name", p.family)
      DetailField("Gender", p.gender?.display.orEmpty())
      DetailField("Birthdate", p.birthDate?.toString().orEmpty())
      DetailField("Phone", p.phone)
      DetailField("Email", p.email)

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = { p.id?.let(onEdit) },
          modifier = Modifier.fillMaxWidth(0.5f),
        ) {
          Icon(Icons.Default.Edit, contentDescription = null)
          Text("Edit", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
          onClick = { confirmingDelete = true },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Default.Delete, contentDescription = null)
          Text("Delete", modifier = Modifier.padding(start = 8.dp))
        }
      }
    }
  }

  if (confirmingDelete) {
    AlertDialog(
      onDismissRequest = { confirmingDelete = false },
      title = { Text("Delete patient?") },
      text = { Text("This cannot be undone.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingDelete = false
            viewModel.delete(onComplete = onBack)
          },
        ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun DetailField(label: String, value: String) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value.ifBlank { "—" },
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
  androidx.compose.foundation.layout.Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator()
  }
}
