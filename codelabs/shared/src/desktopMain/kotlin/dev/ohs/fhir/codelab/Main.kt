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

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.io.File

fun main() {
  initFhirEngine(
    Unit,
    baseUrl = "http://localhost:8080/fhir/",
    debug = true,
    storageDirectory = File(System.getProperty("user.home"), ".fhir-engine-codelab").absolutePath,
  )
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "FHIR Engine Codelab",
      state = WindowState(size = DpSize(420.dp, 800.dp)),
    ) {
      App(Unit, ForegroundSyncLauncher { AppFhirSyncTask(Unit) })
    }
  }
}
