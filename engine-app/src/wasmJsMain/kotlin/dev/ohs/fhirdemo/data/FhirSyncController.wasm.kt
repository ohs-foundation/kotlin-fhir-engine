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
package dev.ohs.fhirdemo.data

import dev.ohs.fhir.engine.sync.CurrentSyncJobStatus
import dev.ohs.fhir.engine.sync.PeriodicSyncJobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * No-op sync controller for web. Background and periodic sync rely on platform schedulers
 * (WorkManager on Android, a coroutine scheduler on Desktop/iOS) that have no browser equivalent,
 * so every method is a stub: the sync screens render but do nothing. Wiring real web sync is future
 * work (see the engine's web notes).
 */
actual class FhirSyncController actual constructor(context: Any) {
  actual suspend fun oneTimeSync(): Flow<CurrentSyncJobStatus> = emptyFlow()

  actual suspend fun cancelOneTimeSync() {}

  actual suspend fun periodicSync(): Flow<PeriodicSyncJobStatus> = emptyFlow()

  actual suspend fun cancelPeriodicSync() {}

  actual suspend fun lastPeriodicSyncStatus(): Flow<PeriodicSyncJobStatus> = emptyFlow()
}
