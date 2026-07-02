# engine-app

A Compose Multiplatform demo app for `kotlin-fhir-engine`. Runs on Android, Desktop, and iOS with shared UI code.

## What's in it

- Patient list with search-by-name
- Patient detail view + delete
- Patient create / edit form (given name, family name, gender, birthdate, phone, email)

Built entirely on Material 3 components — no datacapture library, no other FHIR UI dependencies. The form is hand-rolled in Compose.

## Architecture

```
commonMain/dev/ohs/fhirdemo/
├── App.kt                          # Root composable + screen routing
├── data/
│   ├── Engine.kt                   # Lazy FhirEngineProvider init
│   ├── PatientUiModel.kt           # Plain-Kotlin patient projection
│   ├── PatientRepository.kt        # Engine wrapper + FHIR mapping
│   ├── FhirSyncController.kt       # expect class — one-time & periodic sync API
│   └── TimestampBasedDownloadWorkManagerImpl.kt  # DownloadWorkManager impl
├── nav/Navigator.kt                # Sealed Screen + StateFlow
└── ui/
    ├── theme/Theme.kt              # Material3 colour scheme
    ├── list/   PatientListScreen + ViewModel
    ├── detail/ PatientDetailScreen + ViewModel
    ├── form/   PatientFormScreen + ViewModel
    └── sync/   SyncScreen + SyncViewModel, PeriodicSyncScreen + PeriodicSyncViewModel
```

Platform-specific sync wiring lives under each source set:

```
androidMain/data/
├── DemoFhirSyncWorker.kt           # FhirSyncWorker impl (WorkManager)
└── FhirSyncController.android.kt   # actual — delegates to Sync + WorkManager

iosMain/data/
├── DemoFhirSyncTask.ios.kt         # FhirSyncTask impl
├── IosBgSyncScheduler.kt           # BGProcessingTask scheduler
├── IosFhirSetup.kt                 # bgSyncScheduler singleton + initializeFhirSync()
└── FhirSyncController.ios.kt       # actual — one-time via coroutine, periodic via BGTask

desktopMain/data/
├── DemoFhirSyncTask.desktop.kt     # FhirSyncTask impl
├── Sync.kt                         # Coroutine-based foreground scheduler
└── FhirSyncController.desktop.kt   # actual — delegates to Sync
```

ViewModels are plain Kotlin classes taking a `CoroutineScope` and a repository or controller. State flows through `MutableStateFlow` / `collectAsState`. Navigation is a tiny sealed class — no nav library dependency.

## Running

### Android

Open the project in Android Studio, select the `engine-app` configuration, and Run.

Or from the command line:

```
./gradlew :engine-app:installDebug
```

### Desktop

```
./gradlew :engine-app:run
```

A 420×800 window will open.

### iOS

1. Build the Kotlin framework:

   ```
   ./gradlew :engine-app:linkDebugFrameworkIosSimulatorArm64
   ```
2. Create an Xcode project under `iosApp/` that links the framework at `engine-app/build/bin/iosSimulatorArm64/debugFramework/EngineDemoKit.framework`.
3. Mount the shared `App` in a SwiftUI view:

   ```swift
   import SwiftUI
   import EngineDemoKit

   struct ContentView: View {
     var body: some View {
       ComposeView()
     }
   }

   struct ComposeView: UIViewControllerRepresentable {
     func makeUIViewController(context: Context) -> UIViewController {
       return MainViewControllerKt.MainViewController()
     }
     func updateUIViewController(_: UIViewController, context: Context) {}
   }
   ```

## Sync

The demo app implements sync via `FhirSyncController` — an `expect` class with a platform `actual` on each target. Both one-time and periodic sync are demonstrated in `SyncScreen` and `PeriodicSyncScreen`.

### Android

`FhirSyncController.android.kt` delegates to the engine's `Sync` object (WorkManager-backed). `DemoFhirSyncWorker` extends `FhirSyncWorker` and supplies the engine instance, download work manager, conflict resolver, and upload strategy. No additional setup is required beyond WorkManager being present.

### Desktop

`FhirSyncController.desktop.kt` delegates to the local `Sync` object in `desktopMain/data/Sync.kt`, which uses Kotlin Coroutines for foreground scheduling. Sync runs only while the JVM process is alive.

### iOS

iOS uses two mechanisms:

**One-time sync** — `FhirSyncController.ios.kt` calls `runSync()` directly in a coroutine scope. It hooks into `UIApplicationDidEnterBackgroundNotification` and `UIApplicationWillEnterForegroundNotification` so a running sync is cancelled when the app backgrounds and automatically restarted when it foregrounds.

**Periodic sync** — `IosBgSyncScheduler` wraps `BGProcessingTask`. Each time the OS launches the background task, it creates a fresh `DemoFhirSyncTask`, calls `runSync()`, and re-schedules the next run on success.

#### Required setup in the Xcode project

1. **`Info.plist`** — add the task identifier under `BGTaskSchedulerPermittedIdentifiers`:

   ```xml
   <key>BGTaskSchedulerPermittedIdentifiers</key>
   <array>
     <string>dev.ohs.fhirdemo.sync.periodic</string>
   </array>
   ```
2. **Background modes** — enable `processing` under `UIBackgroundModes` (or in Xcode: _Signing & Capabilities → Background Modes → Background processing_):

   ```xml
   <key>UIBackgroundModes</key>
   <array>
     <string>processing</string>
   </array>
   ```
3. **App launch** — call `initializeFhirSync()` before `applicationDidFinishLaunching` returns so the BGTask handler is registered in time:

   ```swift
   // AppDelegate.swift
   import EngineDemoKit

   func application(_ application: UIApplication,
                    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
       IosFhirSetupKt.initializeFhirSync()
       return true
   }
   ```

#### Testing periodic sync on the Simulator

The OS does not fire `BGProcessingTask` spontaneously in the Simulator. To trigger it manually:

1. Run the app and pause execution in the Xcode debugger at any point after launch.
2. In the LLDB console, run:

   ```
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.ohs.fhirdemo.sync.periodic"]
   ```
3. Resume execution. The task handler fires immediately — sync runs and re-schedules itself on completion.

You can also trigger it from a breakpoint set right after the `bgSyncScheduler.schedule()` call in `FhirSyncController.ios.kt`.

#### Testing periodic sync on a physical device

On a real device the OS decides when to launch the background task based on battery, network, and usage patterns. To force an early launch during development:

1. Trigger the task schedule by calling `periodicSync()` from the app UI at least once (this submits the `BGProcessingTaskRequest`).
2. Background the app.
3. In Xcode, go to _Debug → Simulate Background Fetch_ — or run the same LLDB command above while paused in the debugger.

The task will run and produce a log line from `IosBgSyncScheduler` confirming completion or failure.

## Where the data lives

- **Android**: app's internal storage, managed by Room KMP (`androidx.sqlite.bundled` driver).
- **Desktop**: current working directory (a `.db` file).
- **iOS**: `NSApplicationSupportDirectory` (database and DataStore files)

Clearing the data: the engine exposes `clearDatabase()`. Add a button on the list screen if you want a quick way to wipe everything.
