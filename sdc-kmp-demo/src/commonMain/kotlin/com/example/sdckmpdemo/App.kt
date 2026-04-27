/*
 * Copyright 2025 Google LLC
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

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.sdckmpdemo.sync.createSyncManager
import com.example.sdckmpdemo.sync.providePlatformContext
import com.example.sdckmpdemo.ui.theme.AppTheme
import com.google.fhir.model.r4.Address
import com.google.fhir.model.r4.FhirR4Json
import com.google.fhir.model.r4.HumanName
import kotlinx.serialization.Serializable
import org.jetbrains.compose.ui.tooling.preview.Preview

@Serializable object PatientListDestination

@Composable
fun App() {
  AppTheme {
    Surface {
      val navController: NavHostController = rememberNavController()
      val viewModel: HomeViewModel = viewModel { HomeViewModel() }
      val coroutineScope = rememberCoroutineScope()
      val fhirJson = remember { FhirR4Json() }
      val platformContext = providePlatformContext()
      val syncManager = remember { createSyncManager(platformContext) }
      NavHost(navController = navController, startDestination = PatientListDestination) {
        composable<PatientListDestination> {
          Home(
            viewModel = viewModel,
            syncManager = syncManager,
          )
        }
      }
    }
  }
}
