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
package dev.ohs.fhir.engine.app.data

internal const val PERIODIC_SYNC_TASK_ID = "dev.ohs.fhir.engine.app.sync.periodic"

internal val bgSyncScheduler: IosBgSyncScheduler by lazy {
  IosBgSyncScheduler(
    taskIdentifier = PERIODIC_SYNC_TASK_ID,
    taskFactory = { DemoFhirSyncTask() },
  )
}

/**
 * Registers BGTask handlers with [BGTaskScheduler].
 *
 * Must be called during app launch (before `applicationDidFinishLaunching` returns).
 */
fun initializeFhirSync() {
  bgSyncScheduler.register()
}
