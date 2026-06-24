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
 * UI-only stand-in for one-time sync. The engine module does not implement sync yet, so this
 * simulates the [CurrentSyncJobStatus] lifecycle (Enqueued → Running → Succeeded) to drive the
 * screen. Replace [triggerOneTimeSync] with a real `Sync.oneTimeSync` flow once sync lands.
 */
class SyncViewModel(private val scope: CoroutineScope) {

  private val _status = MutableStateFlow<CurrentSyncJobStatus>(CurrentSyncJobStatus.Idle)
  val status: StateFlow<CurrentSyncJobStatus> = _status.asStateFlow()

  private val _lastSyncTime = MutableStateFlow<String?>(null)
  val lastSyncTime: StateFlow<String?> = _lastSyncTime.asStateFlow()

  private var job: Job? = null

  fun triggerOneTimeSync() {
    if (job?.isActive == true) return
    job =
      scope.launch {
        _status.value = CurrentSyncJobStatus.Enqueued
        delay(600)
        if (!isActive) return@launch
        _status.value = CurrentSyncJobStatus.Running
        delay(2000)
        if (!isActive) return@launch
        _lastSyncTime.value = now().formatTimestamp()
        _status.value = CurrentSyncJobStatus.Succeeded
      }
  }

  fun cancelOneTimeSync() {
    job?.cancel()
    job = null
    _status.value = CurrentSyncJobStatus.Cancelled
  }
}

/** Mirrors the demo's `CurrentSyncJobStatus` states used to drive the UI. */
enum class CurrentSyncJobStatus {
  Idle,
  Enqueued,
  Running,
  Succeeded,
  Failed,
  Cancelled,
}
