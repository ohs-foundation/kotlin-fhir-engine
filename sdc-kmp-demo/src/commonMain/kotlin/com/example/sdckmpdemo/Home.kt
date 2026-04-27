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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdckmpdemo.sync.SyncManager
import com.example.sdckmpdemo.sync.SyncUiState
import com.google.fhir.model.r4.Address
import com.google.fhir.model.r4.HumanName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(
  viewModel: HomeViewModel,
  syncManager: SyncManager,
  modifier: Modifier = Modifier,
) {
  val patients by viewModel.patients.collectAsState()
  val syncState by syncManager.syncState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(syncState) {
    when (val state = syncState) {
      is SyncUiState.Completed -> {
        viewModel.refreshPatients()
        snackbarHostState.showSnackbar("Sync completed")
      }
      is SyncUiState.Error -> {
        snackbarHostState.showSnackbar("Sync error: ${state.message}")
      }
      else -> {}
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Kotlin FHIR Engine Sync Test") },
        actions = {
          if (syncManager.isSyncAvailable) {
            if (syncState is SyncUiState.Syncing) {
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp).padding(end = 4.dp),
                strokeWidth = 2.dp,
              )
            } else {
              IconButton(onClick = { syncManager.triggerSync() }) {
                Icon(Icons.Default.Sync, contentDescription = "Sync")
              }
            }
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { paddingValues ->
    LazyColumn(
      modifier = modifier.fillMaxSize().padding(paddingValues),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      itemsIndexed(patients) { index, patient ->
        Column (modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.Center){
          Text("Patient #$index", style = MaterialTheme.typography.titleMedium,)
          Text(patient.name.humanNames, style = MaterialTheme.typography.bodyMedium,)
        }
      }
    }
  }
}

val HumanName?.displayInApp: String
  get() = this?.given?.plus(family)?.joinToString(separator = " ") { it?.value ?: "" } ?: ""

val List<HumanName?>?.humanNames: String
  get() = this?.joinToString(separator = ", ") { it.displayInApp } ?: ""

val Address?.displayInApp: String
  get() =
    this?.line
      ?.asSequence()
      ?.plus(city)
      ?.plus(state)
      ?.plus(postalCode)
      ?.plus(country)
      ?.map { it?.value }
      ?.joinToString(separator = "\n")
      ?: " "

val List<Address?>?.addresses: String
  get() = this?.joinToString(separator = ", ") { it.displayInApp } ?: ""
