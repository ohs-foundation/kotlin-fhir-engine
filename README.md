# Kotlin FHIR Engine

A Kotlin Multiplatform library for building offline-capable healthcare applications using the HL7 FHIR R4 standard. Provides FHIR resource persistence, synchronization with remote FHIR servers, and a type-safe search API.

## Supported Platforms

- Android
- iOS
- Desktop (JVM)

## Sync

The engine synchronises with a remote FHIR server in two phases: download changed resources from the server, then upload local changes. You wire this up by implementing `FhirSyncTask` and scheduling it with a platform-appropriate mechanism.

### 1. Implement `FhirSyncTask`

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

### 2. Implement `DownloadWorkManager`

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

### 3. Schedule Sync

#### Android — WorkManager (built-in API)

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

#### iOS and Desktop

These platforms require platform-specific scheduling. Implement `FhirSyncTask` directly and invoke `runSync()` from within your scheduler. Sample implementations are provided in the demo app (`engine-app`):

- **iOS** — uses `BGProcessingTask` via [`IosBgSyncScheduler`](engine-app/src/iosMain/kotlin/dev/ohs/fhirdemo/data/IosBgSyncScheduler.kt) to run sync in the background. See also [`DemoFhirSyncTask.ios.kt`](engine-app/src/iosMain/kotlin/dev/ohs/fhirdemo/data/DemoFhirSyncTask.ios.kt). Requires `UIBackgroundModes` → `processing` in your app's `Info.plist`.
- **Desktop (JVM)** — coroutine-based foreground scheduling via [`Sync`](engine-app/src/desktopMain/kotlin/dev/ohs/fhirdemo/data/Sync.kt). See also [`DemoFhirSyncTask.desktop.kt`](engine-app/src/desktopMain/kotlin/dev/ohs/fhirdemo/data/DemoFhirSyncTask.desktop.kt). Sync only runs while the JVM process is alive; there is no OS-level background scheduling.

### Sync Status

All scheduling paths emit a flow of status updates:

| Status                           | Description                            |
|----------------------------------|----------------------------------------|
| `CurrentSyncJobStatus.Enqueued`  | Job is queued, not yet started         |
| `CurrentSyncJobStatus.Running`   | Sync is in progress                    |
| `CurrentSyncJobStatus.Succeeded` | Sync completed successfully            |
| `CurrentSyncJobStatus.Failed`    | Sync failed (after configured retries) |
| `CurrentSyncJobStatus.Cancelled` | Sync was cancelled                     |

For periodic sync, `PeriodicSyncJobStatus` combines `currentSyncJobStatus` with `lastSyncJobStatus` — the terminal result of the most recently completed cycle.

## License

Licensed under the Apache License, Version 2.0.
