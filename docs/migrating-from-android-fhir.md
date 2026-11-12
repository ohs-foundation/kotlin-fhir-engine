# Migrating from android-fhir

The KMP engine is a Kotlin Multiplatform port of the android-fhir `engine` module. Most app code
carries over. This page lists what stays the same and what you need to change.

## What stays the same

- **The `FhirEngine` API.** All 14 methods, with the same names and parameters: `create`, `get`,
  `update`, `delete`, `search`, `count`, `syncUpload`, `syncDownload`, `getLastSyncTimeStamp`,
  `clearDatabase`, `getLocalChanges`, `purge` (one and many) and `withTransaction`.
- **Your data.** The database uses the same file name, and the KMP engine includes android-fhir's
  migrations, so an android-fhir database (schema version 10) is migrated to the KMP engine's
  version 12 and keeps its data. Databases from KMP engine releases 2.0.0-alpha01 to alpha04 are
  converted in place. The encryption key comes from the Android Keystore the same way as in
  android-fhir, but opening an encrypted android-fhir database hasn't been verified yet. The sync
  state android-fhir stored, such as the last sync time and sync job statuses, isn't carried over.
  It's empty until the first sync.
- **Sync on Android.** `FhirSyncWorker`, `DownloadWorkManager`, `ConflictResolver`,
  `UploadStrategy`, `Sync.oneTimeSync` and `Sync.periodicSync` with WorkManager.
- **The search DSL.** `filter`, `sort`, `count`, `from`, `has`, `include` and `revInclude` work
  the same way. There's no `search(resourceType) { }` overload, and filter values use the types in
  the table below.

## What changes

| Area | android-fhir | KMP engine |
|---|---|---|
| Engine package | `com.google.android.fhir` | `dev.ohs.fhir.engine` (artifact `dev.ohs.fhir:fhir-engine`) |
| FHIR model | HAPI (`org.hl7.fhir.r4.model`) | kotlin-fhir (`dev.ohs.fhir.model.r4`) |
| Search parameters | HAPI constants, such as `Patient.GIVEN` | By name, such as `StringClientParam("given")` |
| Search prefixes | `ParamPrefixEnum.GREATERTHAN` | `SearchComparator.Gt`, from `dev.ohs.fhir.model.r4.SearchParameter` |
| Search values | `java.math.BigDecimal`, `DateType`, `DateTimeType` | `com.ionspin.kotlin.bignum.decimal.BigDecimal`, `FhirDate`, `FhirDateTime` |
| Custom search parameters | FHIR `SearchParameter` resources | `SearchParamDefinition(name, type, path)`, with the KMP engine's own `SearchParamType`. The path must start with the resource type. |
| Time types | `java.time.OffsetDateTime` from `getLastSyncTimeStamp`, `java.time.Instant` in `LocalChange.timestamp` | The KMP engine's own `OffsetDateTime`, and `kotlin.time.Instant` |
| `Sync.getLastSyncTimestamp(context)` on Android | Returns `OffsetDateTime?` | A `suspend` function that returns `kotlin.time.Instant?` |
| HTTP | Retrofit and OkHttp | Ktor, on OkHttp on Android, Java on desktop, Darwin on iOS, and the browser on web |
| `HttpLogger` | `HttpLogger(Configuration(level, headersToIgnore: List<String>?), log)` | `HttpLogger(level, headersToIgnore: Set<String>)`, with no callback. Logs go to Kermit at verbose level. |
| `httpCache` | OkHttp disk cache, `cacheDir` is a `File` | Ktor's in-memory cache. `cacheDir` is a `String`, and it and `maxSize` are ignored. |
| Encryption setting | `enableEncryptionIfSupported` | `enableEncryption`. Throws at init where it can't encrypt: desktop, web, and iOS without SQLCipher linked. |
| Storage location | Android app storage | Android and iOS use app storage. Desktop stores files in `storageDirectory` (default `~/.fhir-engine`). Web uses it as a prefix for the database file name. |
| Starting the engine | `getInstance(context)` works without `init`, using a default configuration | Call `init(configuration, context)` first, passing the context on Android. `getInstance` throws without it. |
| `FhirEngineProvider.cleanup()` | Throws unless `testMode` is on | Works without `testMode`, but needs `@OptIn(FhirEngineTestApi::class)` |
| Sync configuration | `RepeatInterval` and `BackoffCriteria` take a `TimeUnit`. WorkManager's `Constraints` and `BackoffPolicy`. | They take a `kotlin.time.Duration`. The KMP engine's own `SyncConstraints`, which needs a network connection by default, and `BackoffPolicy`. `PeriodicSyncConfiguration` adds `syncTimeout`. |
| Sync scheduling | WorkManager | WorkManager on Android. Other platforms implement `FhirSyncTask`, which is new, and schedule sync themselves. |

See [Known issues](known-issues.md) for what doesn't work yet.
