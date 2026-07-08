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

import dev.ohs.fhir.sync.CurrentSyncJobStatus
import dev.ohs.fhir.sync.SyncJobStatus
import dev.ohs.fhirdemo.data.FhirSyncController
import dev.ohs.fhirdemo.util.formatTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class PeriodicSyncViewModel(
  private val scope: CoroutineScope,
  private val controller: FhirSyncController,
) {
  private val _uiState = MutableStateFlow(PeriodicSyncUiState())
  val uiState: StateFlow<PeriodicSyncUiState> = _uiState.asStateFlow()

  private var job: Job? = null
  private var loadJob: Job? = null

  init {
    loadJob =
      scope.launch {
        controller.lastPeriodicSyncStatus().collect { status ->
          _uiState.value =
            PeriodicSyncUiState(
              lastSyncStatus = status.lastSyncJobStatus?.let { it::class.simpleName },
              lastSyncTime =
                status.lastSyncJobStatus
                  ?.timestamp
                  ?.toLocalDateTime(TimeZone.currentSystemDefault())
                  ?.formatTimestamp(),
            )
        }
      }
  }

  fun start() {
    if (job?.isActive == true) return
    loadJob?.cancel()
    job =
      scope.launch {
        controller.periodicSync().collect { status ->
          _uiState.value =
            PeriodicSyncUiState(
              lastSyncStatus = status.lastSyncJobStatus?.let { it::class.simpleName },
              lastSyncTime =
                status.lastSyncJobStatus
                  ?.timestamp
                  ?.toLocalDateTime(TimeZone.currentSystemDefault())
                  ?.formatTimestamp(),
              currentStatus =
                when (status.currentSyncJobStatus) {
                  is CurrentSyncJobStatus.Enqueued -> "Enqueued"
                  is CurrentSyncJobStatus.Running -> "Running"
                  is CurrentSyncJobStatus.Succeeded -> "Succeeded"
                  is CurrentSyncJobStatus.Failed -> "Failed"
                  is CurrentSyncJobStatus.Cancelled -> "Cancelled"
                  is CurrentSyncJobStatus.Blocked -> "Blocked"
                },
              progress =
                when (val s = status.currentSyncJobStatus) {
                  is CurrentSyncJobStatus.Running -> {
                    val jobStatus = s.inProgressSyncJob
                    if (jobStatus is SyncJobStatus.InProgress && jobStatus.total > 0) {
                      jobStatus.completed * 100 / jobStatus.total
                    } else {
                      null
                    }
                  }
                  else -> null
                },
            )
        }
      }
  }

  fun cancel() {
    job?.cancel()
    job = null
    loadJob?.cancel()
    loadJob = null
    scope.launch { controller.cancelPeriodicSync() }
    _uiState.value = _uiState.value.copy(currentStatus = "Cancelled", progress = null)
  }
}

data class PeriodicSyncUiState(
  val lastSyncStatus: String? = null,
  val lastSyncTime: String? = null,
  val currentStatus: String? = null,
  val progress: Int? = null,
)
