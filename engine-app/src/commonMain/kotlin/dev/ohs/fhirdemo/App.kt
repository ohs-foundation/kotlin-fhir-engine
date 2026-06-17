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

package dev.ohs.fhirdemo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.ohs.fhirdemo.data.PatientRepository
import dev.ohs.fhirdemo.data.fhirEngine
import dev.ohs.fhirdemo.nav.Navigator
import dev.ohs.fhirdemo.nav.Screen
import dev.ohs.fhirdemo.ui.crud.CrudScreen
import dev.ohs.fhirdemo.ui.crud.CrudViewModel
import dev.ohs.fhirdemo.ui.detail.PatientDetailScreen
import dev.ohs.fhirdemo.ui.detail.PatientDetailViewModel
import dev.ohs.fhirdemo.ui.form.PatientFormScreen
import dev.ohs.fhirdemo.ui.form.PatientFormViewModel
import dev.ohs.fhirdemo.ui.home.HomeScreen
import dev.ohs.fhirdemo.ui.list.PatientListScreen
import dev.ohs.fhirdemo.ui.list.PatientListViewModel
import dev.ohs.fhirdemo.ui.sync.PeriodicSyncScreen
import dev.ohs.fhirdemo.ui.sync.PeriodicSyncViewModel
import dev.ohs.fhirdemo.ui.sync.SyncScreen
import dev.ohs.fhirdemo.ui.sync.SyncViewModel
import dev.ohs.fhirdemo.ui.theme.DemoTheme

@Composable
fun App(platformContext: Any = Unit) {
  val scope = rememberCoroutineScope()
  val repository = remember { PatientRepository(fhirEngine(platformContext)) }
  val navigator = remember { Navigator() }
  val current by navigator.current.collectAsState()
  val listViewModel = remember { PatientListViewModel(repository, scope) }

  DemoTheme {
    when (val screen = current) {
      is Screen.Home ->
        HomeScreen(
          onFindPatient = {
            listViewModel.reload()
            navigator.go(Screen.List)
          },
          onSync = { navigator.go(Screen.Sync) },
          onPeriodicSync = { navigator.go(Screen.PeriodicSync) },
          onCrud = { navigator.go(Screen.Crud) },
        )

      is Screen.List ->
        PatientListScreen(
          viewModel = listViewModel,
          onAddClick = { navigator.go(Screen.NewPatient) },
          onPatientClick = { id -> navigator.go(Screen.Detail(id)) },
          onBack = { navigator.back() },
        )

      is Screen.Detail -> {
        val vm = remember(screen.patientId) {
          PatientDetailViewModel(screen.patientId, repository, scope)
        }
        PatientDetailScreen(
          viewModel = vm,
          onBack = {
            listViewModel.reload()
            navigator.back()
          },
          onEdit = { id -> navigator.go(Screen.EditPatient(id)) },
        )
      }

      is Screen.NewPatient -> {
        val vm = remember { PatientFormViewModel(patientId = null, repository, scope) }
        PatientFormScreen(
          viewModel = vm,
          onBack = {
            listViewModel.reload()
            navigator.back()
          },
        )
      }

      is Screen.EditPatient -> {
        val vm = remember(screen.patientId) {
          PatientFormViewModel(patientId = screen.patientId, repository, scope)
        }
        PatientFormScreen(
          viewModel = vm,
          onBack = {
            listViewModel.reload()
            navigator.back()
          },
        )
      }

      is Screen.Sync -> {
        val vm = remember { SyncViewModel(scope) }
        SyncScreen(viewModel = vm, onBack = { navigator.back() })
      }

      is Screen.PeriodicSync -> {
        val vm = remember { PeriodicSyncViewModel(scope) }
        DisposableEffect(vm) { onDispose { vm.cancel() } }
        PeriodicSyncScreen(viewModel = vm, onBack = { navigator.back() })
      }

      is Screen.Crud -> {
        val vm = remember { CrudViewModel(repository, scope) }
        CrudScreen(viewModel = vm, onBack = { navigator.back() })
      }
    }
  }
}
