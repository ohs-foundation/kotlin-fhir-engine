# Kotlin FHIR Engine

[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine?color=yellow&label=fhir-engine)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-android?color=yellow&label=android)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-android)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-desktop?color=yellow&label=desktop)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-desktop)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-wasm-js?color=yellow&label=wasm-js)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-wasm-js)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-iossimulatorarm64?color=yellow&label=iossimulatorarm64)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-iossimulatorarm64)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-iosarm64?color=yellow&label=iosarm64)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-iosarm64)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A Kotlin Multiplatform library for building offline-capable healthcare applications using the HL7
FHIR R4 standard. It provides on-device FHIR resource persistence, a type-safe search API, and
synchronization with remote FHIR servers.

## Supported platforms

The library's support for different
[target platforms](https://kotlinlang.org/docs/multiplatform-dsl-reference.html#targets)
is listed in the following table:

| Target platform                    | Gradle target       | Artifact suffix      | Support |
|:-----------------------------------|:--------------------|:---------------------|:--------|
| Kotlin/JVM (Desktop)               | `jvm("desktop")`    | `-desktop`           | ✅       |
| Kotlin/Wasm                        | `wasmJs`            | `-wasm-js`           | ✅       |
| Android applications and libraries | `android`           | `-android`           | ✅       |
| iOS (Apple silicon simulator)      | `iosSimulatorArm64` | `-iossimulatorarm64` | ✅       |
| iOS (device)                       | `iosArm64`          | `-iosarm64`          | ✅       |

## Demo app

The `engine-app` module is a multiplatform demo application (Android, Desktop, iOS, and Web). Run it
with:

```bash
./gradlew :engine-app:run                              # Desktop
./gradlew :engine-app:wasmJsBrowserDevelopmentRun      # Web
./gradlew :engine-app:installDebug                     # Android
```

For iOS, build the framework and run the demo from Xcode on a simulator.

## User guide

### Adding the library dependency to your project

To use the Kotlin FHIR Engine library in your project, you need to add the library dependency to your
project. To do that, first make sure to include the `mavenCentral()`[^1] repository in the
`build.gradle.kts` file in your project root.

[^1]: Early versions of this library were published under the group ID `com.google.android.fhir` and
artifact ID `engine` on [Google Maven](https://maven.google.com/web/index.html#com.google.android.fhir:engine).

```
// build.gradle.kts
repositories {
    // Other repositories such as gradlePluginPortal() and google()
    mavenCentral()
}
```

Next, follow the instructions for your specific project type.

#### Kotlin Multiplatform Projects

For Kotlin Multiplatform projects, add the dependency to the shared `commonMain` source set within
the `kotlin` block of the module's `build.gradle.kts` file (e.g., `composeApp/build.gradle.kts` or
`shared/build.gradle.kts`). This makes the library available across all platforms in your project.

```
// e.g., composeApp/build.gradle.kts or shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.ohs.fhir:fhir-engine:2.0.0-alpha01")
        }
    }
}
```

#### Android projects

For Android projects, add the dependency to the `dependencies` block in the module's
`build.gradle.kts` file (e.g., `app/build.gradle.kts`).

```
// e.g., app/build.gradle.kts
dependencies {
    implementation("dev.ohs.fhir:fhir-engine:2.0.0-alpha01")
}
```

### Persisting and searching resources

Initialize `FhirEngineProvider` once at startup, then use the `FhirEngine` instance for CRUD and
search. On Android pass the application `Context`; on other platforms pass `Unit` (the default).

```kotlin
FhirEngineProvider.init(
    FhirEngineConfiguration(
        // Optional: a remote server to sync against, and custom search parameters.
        serverConfiguration = ServerConfiguration(baseUrl = "https://hapi.fhir.org/baseR4/"),
    ),
    platformContext, // Android: applicationContext; other platforms: Unit
)

val fhirEngine = FhirEngineProvider.getInstance(platformContext)

// Create (returns the assigned ids)
val ids = fhirEngine.create(Patient(id = "patient-1"))

// Read
val patient = fhirEngine.get(ResourceType.Patient, "patient-1") as Patient

// Update / delete
fhirEngine.update(patient)
fhirEngine.delete(ResourceType.Patient, "patient-1")

// Search (an empty block matches all; add filters/sort inside the block)
val patients = fhirEngine.search<Patient> {}
```

### Synchronizing with a FHIR server

The engine synchronises with a remote FHIR server in two phases: download changed resources from the server, then upload local changes. You wire this up by implementing `FhirSyncTask` and scheduling it with a platform-appropriate mechanism.

#### 1. Implement `FhirSyncTask`

`FhirSyncTask` defines what the sync job needs. Implement all four methods:

```kotlin
class MyFhirSyncTask : FhirSyncTask {
    override fun getFhirEngine(): FhirEngine = /* your FhirEngine instance */
    override fun getDownloadWorkManager(): DownloadWorkManager = MyDownloadWorkManager()
    override fun getConflictResolver(): ConflictResolver = AcceptLocalConflictResolver
    override fun getUploadStrategy(): UploadStrategy =
        UploadStrategy.forBundleRequest(
            methodForCreate = HttpCreateMethod.PUT,
            methodForUpdate = HttpUpdateMethod.PATCH,
            squash = true,
            bundleSize = 500,
        )
}
```

- **`getDownloadWorkManager()`** — controls what resources are requested and how responses are processed.
- **`getConflictResolver()`** — resolves conflicts between local and remote versions. Built-in options: `AcceptLocalConflictResolver` and `AcceptRemoteConflictResolver`.
- **`getUploadStrategy()`** — controls how local changes are sent to the server.

#### 2. Implement `DownloadWorkManager`

`DownloadWorkManager` drives the download phase by generating requests and processing each response:

```kotlin
class MyDownloadWorkManager : DownloadWorkManager {
    override suspend fun getNextRequest(): DownloadRequest? {
        // Return the next request, or null when all resources have been downloaded
    }

    override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> {
        // Return URLs to fetch resource counts (used for progress display)
        return emptyMap()
    }

    override suspend fun processResponse(response: Resource): Collection<Resource> {
        // Extract and return resources to save from the server response
        return (response as? Bundle)?.entry?.mapNotNull { it.resource } ?: emptyList()
    }
}
```

For a timestamp-based approach that only downloads resources modified since the last sync, see `TimestampBasedDownloadWorkManagerImpl` in the demo app (`engine-app`).

#### 3. Schedule Sync

##### Android — WorkManager (built-in API)

Android has first-class support via `FhirSyncWorker` (a `CoroutineWorker` that implements `FhirSyncTask`) and the `Sync` object. Extend `FhirSyncWorker` instead of implementing `FhirSyncTask` directly:

```kotlin
class MyFhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    FhirSyncWorker(appContext, workerParams) {

    override fun getFhirEngine() = /* your FhirEngine instance */
    override fun getDownloadWorkManager() = MyDownloadWorkManager()
    override fun getConflictResolver() = AcceptLocalConflictResolver
    override fun getUploadStrategy() = UploadStrategy.forBundleRequest(
        methodForCreate = HttpCreateMethod.PUT,
        methodForUpdate = HttpUpdateMethod.PATCH,
        squash = true,
        bundleSize = 500,
    )
}
```

Schedule it using `Sync`:

```kotlin
// One-time sync
val statusFlow = Sync.oneTimeSync<MyFhirSyncWorker>(context)
statusFlow.collect { status -> /* handle CurrentSyncJobStatus */ }

// Periodic sync every 15 minutes, requiring network connectivity
val periodicFlow = Sync.periodicSync<MyFhirSyncWorker>(
    context,
    PeriodicSyncConfiguration(
        repeat = RepeatInterval(15.minutes),
        syncConstraints = SyncConstraints(requiredNetworkType = NetworkType.CONNECTED),
    ),
)
periodicFlow.collect { status -> /* handle PeriodicSyncJobStatus */ }

// Cancel
Sync.cancelOneTimeSync<MyFhirSyncWorker>(context)
Sync.cancelPeriodicSync<MyFhirSyncWorker>(context)
```

##### iOS and Desktop

These platforms require platform-specific scheduling. Implement `FhirSyncTask` directly and invoke `runSync()` from within your scheduler. Sample implementations are provided in the demo app (`engine-app`):

- **iOS** — uses `BGProcessingTask` via [`IosBgSyncScheduler`](engine-app/src/iosMain/kotlin/dev/ohs/fhirdemo/data/IosBgSyncScheduler.kt) to run sync in the background. See also [`DemoFhirSyncTask.ios.kt`](engine-app/src/iosMain/kotlin/dev/ohs/fhirdemo/data/DemoFhirSyncTask.ios.kt). Requires `UIBackgroundModes` → `processing` in your app's `Info.plist`.
- **Desktop (JVM)** — coroutine-based foreground scheduling via [`Sync`](engine-app/src/desktopMain/kotlin/dev/ohs/fhirdemo/data/Sync.kt). See also [`DemoFhirSyncTask.desktop.kt`](engine-app/src/desktopMain/kotlin/dev/ohs/fhirdemo/data/DemoFhirSyncTask.desktop.kt). Sync only runs while the JVM process is alive; there is no OS-level background scheduling.

#### Sync Status

All scheduling paths emit a flow of status updates:

| Status                           | Description                            |
|----------------------------------|----------------------------------------|
| `CurrentSyncJobStatus.Enqueued`  | Job is queued, not yet started         |
| `CurrentSyncJobStatus.Running`   | Sync is in progress                    |
| `CurrentSyncJobStatus.Succeeded` | Sync completed successfully            |
| `CurrentSyncJobStatus.Failed`    | Sync failed (after configured retries) |
| `CurrentSyncJobStatus.Cancelled` | Sync was cancelled                     |

For periodic sync, `PeriodicSyncJobStatus` combines `currentSyncJobStatus` with `lastSyncJobStatus`, the terminal result of the most recently completed cycle.

### Web (Wasm)

Persistence uses [Room](https://developer.android.com/kotlin/multiplatform/room). Web support
requires **Room 3** (`androidx.room3`), Room 2 has no Wasm target, which is why the engine uses
`androidx.room3.*` on all platforms.

Android, iOS, and Desktop use the bundled native SQLite driver (`BundledSQLiteDriver` from
`sqlite-bundled`), which has no Wasm build. On Wasm the database instead uses `WebWorkerSQLiteDriver`,
backed by a SQLite-WASM Web Worker. That worker and its driver live in the `:sqlite-wasm-worker`
module, see its [README](sqlite-wasm-worker/README.md). `engine`'s js/wasmJs targets depend on it
via `api`, so it's published to Maven Central alongside `engine` — see
[Publishing](#publishing) below.

## Developer guide

### Publishing

To publish a new release, first update `mavenVersion` in `gradle.properties` to the new version. Then
follow one of the methods below.

`mavenGroupId` is shared by both published modules, `:engine` and `:sqlite-wasm-worker`, but each
has its own artifactId and version (`mavenArtifactId`/`mavenVersion` for `:engine`,
`sqliteWasmWorkerArtifactId`/`sqliteWasmWorkerVersion` for `:sqlite-wasm-worker`), released
independently. `:sqlite-wasm-worker` is a dependency of `engine`'s js/wasmJs targets though, so
whatever `sqliteWasmWorkerVersion` currently points to must already be published to Maven Central
(in this release or a prior one) before `:engine` is published, or those targets won't resolve for
consumers. The commands below publish everything; scope one to a single module (e.g.
`:sqlite-wasm-worker:publishToMavenCentral`) to test one module's publication in isolation.

#### Maven Local

To publish artifacts to your local Maven repository (`~/.m2/repository`) for local development and
testing, run:

```bash
./gradlew publishToMavenLocal
```

#### Maven Central

Publishing to Maven Central requires two sets of credentials:

1. Maven Central credentials: your Sonatype portal username and password tokens.
2. GPG signing: a GPG key and its passphrase, used to sign all published artifacts.

See the
[Kotlin Multiplatform Publishing Guide](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries-to-maven.html)
and the
[Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-portal-guide/) for
more information on how to set up these credentials.

##### Publishing to Maven Central manually

For manual publishing, store the credentials in the global `~/.gradle/gradle.properties` in your
environment (not the project's `gradle.properties`) so they are never committed to the repository:

```properties
# Maven Central Credentials
mavenCentralUsername=YOUR_USERNAME_TOKEN
mavenCentralPassword=YOUR_PASSWORD_TOKEN

# GPG Signing (file-based)
signing.keyId=YOUR_KEY_ID
signing.password=YOUR_KEY_PASSWORD
signing.secretKeyRingFile=/path/to/secring.gpg
```

Then run:

```bash
./gradlew publishToMavenCentral
```

##### Publishing to Maven Central using GitHub Actions

The project includes a GitHub Actions [workflow](.github/workflows/publish.yml) that publishes to
Maven Central when a new GitHub release (or pre-release) is created.

The workflow requires the following GitHub organization or repository secrets (already set up):

| Secret                   | Description                                                                           |
|:-------------------------|:--------------------------------------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME` | Same as `mavenCentralUsername`                                                        |
| `MAVEN_CENTRAL_PASSWORD` | Same as `mavenCentralPassword`                                                        |
| `GPG_KEY_CONTENTS`       | Needs to be exported using the command `gpg --armor --export-secret-keys YOUR_KEY_ID` |
| `SIGNING_PASSWORD`       | Same as `signing.password`                                                            |

## License

Licensed under the Apache License, Version 2.0.
