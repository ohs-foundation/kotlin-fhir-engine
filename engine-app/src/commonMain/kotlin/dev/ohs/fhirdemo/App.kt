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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.ohs.fhirdemo.data.PatientRepository
import dev.ohs.fhirdemo.data.fhirEngine
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
  val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
  val listViewModel = remember { PatientListViewModel(repository, scope) }

  fun back() {
    backStack.removeLastOrNull()
  }

  DemoTheme {
    NavDisplay(
      backStack = backStack,
      onBack = { back() },
      entryProvider =
        entryProvider {
          entry<Screen.Home> {
            HomeScreen(
              onFindPatient = {
                listViewModel.reload()
                backStack.add(Screen.List)
              },
              onSync = { backStack.add(Screen.Sync) },
              onPeriodicSync = { backStack.add(Screen.PeriodicSync) },
              onCrud = { backStack.add(Screen.Crud) },
            )
          }

          entry<Screen.List> {
            PatientListScreen(
              viewModel = listViewModel,
              onAddClick = { backStack.add(Screen.NewPatient) },
              onPatientClick = { id -> backStack.add(Screen.Detail(id)) },
              onBack = { back() },
            )
          }

          entry<Screen.Detail> { key ->
            val vm =
              remember(key.patientId) {
                PatientDetailViewModel(key.patientId, repository, scope)
              }
            PatientDetailScreen(
              viewModel = vm,
              onBack = {
                listViewModel.reload()
                back()
              },
              onEdit = { id -> backStack.add(Screen.EditPatient(id)) },
            )
          }

          entry<Screen.NewPatient> {
            val vm = remember { PatientFormViewModel(patientId = null, repository, scope) }
            PatientFormScreen(
              viewModel = vm,
              onBack = {
                listViewModel.reload()
                back()
              },
            )
          }

          entry<Screen.EditPatient> { key ->
            val vm =
              remember(key.patientId) {
                PatientFormViewModel(patientId = key.patientId, repository, scope)
              }
            PatientFormScreen(
              viewModel = vm,
              onBack = {
                listViewModel.reload()
                back()
              },
            )
          }

          entry<Screen.Sync> {
            val vm = remember { SyncViewModel(scope) }
            SyncScreen(viewModel = vm, onBack = { back() })
          }

          entry<Screen.PeriodicSync> {
            val vm = remember { PeriodicSyncViewModel(scope) }
            DisposableEffect(vm) { onDispose { vm.cancel() } }
            PeriodicSyncScreen(viewModel = vm, onBack = { back() })
          }

          entry<Screen.Crud> {
            val vm = remember { CrudViewModel(repository, scope) }
            CrudScreen(viewModel = vm, onBack = { back() })
          }
        },
    )
  }
}
