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
│   └── PatientRepository.kt        # Engine wrapper + FHIR mapping
├── nav/Navigator.kt                # Sealed Screen + StateFlow
└── ui/
    ├── theme/Theme.kt              # Material3 colour scheme
    ├── list/   PatientListScreen + ViewModel
    ├── detail/ PatientDetailScreen + ViewModel
    └── form/   PatientFormScreen + ViewModel
```

ViewModels are plain Kotlin classes taking a `CoroutineScope` and `PatientRepository`. State flows through `MutableStateFlow` / `collectAsState`. Navigation is a tiny sealed class — no nav library dependency.

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

## Where the data lives

- **Android**: app's internal storage, managed by Room KMP (`androidx.sqlite.bundled` driver).
- **Desktop**: current working directory (a `.db` file).
- **iOS**: `NSDocumentDirectory` (a `.db` file).

Clearing the data: the engine exposes `clearDatabase()`. Add a button on the list screen if you want a quick way to wipe everything.
