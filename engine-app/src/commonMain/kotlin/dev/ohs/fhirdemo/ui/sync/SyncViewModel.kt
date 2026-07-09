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
import dev.ohs.fhirdemo.data.FhirSyncController
import dev.ohs.fhirdemo.util.formatTimestamp
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModel(
  private val scope: CoroutineScope,
  private val controller: FhirSyncController,
) {
  private val _lastSyncTime = MutableStateFlow<String?>(null)
  val lastSyncTime: StateFlow<String?> = _lastSyncTime.asStateFlow()

  private val _oneTimeSyncTrigger =
    MutableSharedFlow<Boolean>(
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  val pollState: SharedFlow<CurrentSyncJobStatus> =
    _oneTimeSyncTrigger
      .flatMapLatest { controller.oneTimeSync() }
      .onEach { if (it is CurrentSyncJobStatus.Succeeded) updateLastSyncTimestamp(it.timestamp) }
      .shareIn(scope, SharingStarted.Eagerly, replay = 0)

  fun triggerOneTimeSync() {
    _oneTimeSyncTrigger.tryEmit(true)
  }

  fun cancelOneTimeSync() {
    scope.launch { controller.cancelOneTimeSync() }
  }

  fun updateLastSyncTimestamp(lastSync: Instant? = null) {
    _lastSyncTime.value =
      lastSync?.toLocalDateTime(TimeZone.currentSystemDefault())?.formatTimestamp()
  }
}
