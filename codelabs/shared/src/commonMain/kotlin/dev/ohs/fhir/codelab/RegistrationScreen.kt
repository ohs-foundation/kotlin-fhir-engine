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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.ohs.fhir.codelab.resources.Res

/** Shows the patient registration form. [onClose] returns to the patient list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(viewModel: PatientListViewModel, onClose: () -> Unit) {
  var questionnaireJson by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    questionnaireJson = Res.readBytes("files/questionnaire.json").decodeToString()
  }
  val scope = rememberCoroutineScope()

  // The form keeps its answers in a view model. A new ViewModelStore for each visit, cleared when
  // the screen closes, makes the form start empty every time.
  val formStore = remember {
    object : ViewModelStoreOwner {
      override val viewModelStore = ViewModelStore()
    }
  }
  DisposableEffect(Unit) { onDispose { formStore.viewModelStore.clear() } }

  CompositionLocalProvider(LocalViewModelStoreOwner provides formStore) {
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("Register a patient") },
          actions = { TextButton(onClick = onClose) { Text("Back") } },
        )
      },
    ) { padding ->
      Box(Modifier.padding(padding).fillMaxSize()) {
        // 8 Replace with code from the codelab to display the registration form.
        Text("The registration form goes here.", Modifier.align(Alignment.Center))
      }
    }
  }
}
