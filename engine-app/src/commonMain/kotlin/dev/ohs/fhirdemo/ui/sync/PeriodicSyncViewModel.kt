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
package dev.ohs.fhirdemo.ui.sync

import dev.ohs.fhirdemo.util.formatTimestamp
import dev.ohs.fhirdemo.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI-only stand-in for periodic sync. Simulates a repeating sync job that reports progress and a
 * last-sync result, mirroring the demo's `PeriodicSyncViewModel`. Replace with a real
 * `Sync.periodicSync` flow once the engine implements sync.
 */
class PeriodicSyncViewModel(private val scope: CoroutineScope) {

  private val _uiState = MutableStateFlow(PeriodicSyncUiState())
  val uiState: StateFlow<PeriodicSyncUiState> = _uiState.asStateFlow()

  private var job: Job? = null

  fun start() {
    if (job?.isActive == true) return
    job =
      scope.launch {
        while (isActive) {
          _uiState.value = _uiState.value.copy(currentStatus = "Running", progress = 0)
          for (pct in 0..100 step 20) {
            _uiState.value = _uiState.value.copy(progress = pct)
            delay(300)
            if (!isActive) return@launch
          }
          _uiState.value =
            _uiState.value.copy(
              currentStatus = "Enqueued",
              progress = null,
              lastSyncStatus = "Succeeded",
              lastSyncTime = now().formatTimestamp(),
            )
          delay(5000)
        }
      }
  }

  fun cancel() {
    job?.cancel()
    job = null
    _uiState.value = _uiState.value.copy(currentStatus = "Cancelled", progress = null)
  }
}

data class PeriodicSyncUiState(
  val lastSyncStatus: String? = null,
  val lastSyncTime: String? = null,
  val currentStatus: String? = null,
  val progress: Int? = null,
)
