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
package dev.ohs.fhir.engine.benchmark

import android.content.Context
import android.os.Build
import java.io.File
import kotlin.time.Clock

/**
 * Holds the Android `Context` the harness runs against.
 *
 * Android is the one target where the engine needs a real platform context, and this module is a
 * library with no way to obtain one. The driver app and the macrobenchmark setup both set this
 * before running anything.
 */
object AndroidBenchmarkContext {
  var context: Context? = null
}

internal actual fun benchmarkPlatformContext(): Any =
  checkNotNull(AndroidBenchmarkContext.context) {
    "AndroidBenchmarkContext.context was not set. Set it to the application context before running."
  }

/** Ignored by the engine on Android, which always uses `getDatabasePath("resources.db")`. */
internal actual fun benchmarkStorageDirectory(): String? = null

internal actual fun platformDescriptor() =
  PlatformDescriptor(
    target = "android",
    os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    cpuCount = Runtime.getRuntime().availableProcessors(),
  )

internal actual fun supportsFreshDatabase(): Boolean = true

internal actual fun nowIso8601(): String = Clock.System.now().toString()

/**
 * Android ignores `storageDirectory`, so a cold database has to come from deleting the file the
 * engine hard-codes rather than from pointing at a new location.
 */
internal actual suspend fun deleteBenchmarkDatabase(platformContext: Any) {
  (platformContext as? Context)?.deleteDatabase(ENGINE_DATABASE_NAME)
}

internal actual suspend fun emitReport(fileName: String, json: String) {
  val context = AndroidBenchmarkContext.context ?: return
  val directory = context.getExternalFilesDir(null) ?: context.filesDir
  File(directory, fileName).writeText(json)
}

/** Matches `DatabaseBuilder.android.kt`. */
private const val ENGINE_DATABASE_NAME = "resources.db"
