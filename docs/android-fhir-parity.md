# Parity with the android-fhir engine

The engine is a Kotlin Multiplatform port of the
[android-fhir](https://github.com/google/android-fhir) `engine` module. The FHIR model is
[kotlin-fhir](https://github.com/ohs-foundation/kotlin-fhir) instead of HAPI and the HTTP client is
Ktor instead of Retrofit. This page documents how closely the port matches the original's API and
behavior. For conformance against the FHIR specifications themselves see
[Conformance](conformance.md).

## Status legend

- ✅ Matches the original.
- ⚠️ Present but partial or diverges from the original. See *Notes*.
- ❌ Accepted but not functional yet.

## FhirEngine API

All 14 methods of the original `FhirEngine` interface are present with identical signatures in
[`FhirEngine.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/FhirEngine.kt). ✅
These are `create`, `get`, `update`, `delete`, `search`, `count`, `syncUpload` (deprecated upstream
too), `syncDownload` (deprecated), `getLastSyncTimeStamp`, `clearDatabase`, `getLocalChanges`,
`purge` (single and bulk) and `withTransaction`.

Resource types come from the kotlin-fhir model. `getLastSyncTimeStamp` returns the engine's own
multiplatform [`OffsetDateTime`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/OffsetDateTime.kt)
instead of `java.time.OffsetDateTime`.

## Configuration

[`FhirEngineConfiguration.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/FhirEngineConfiguration.kt)
keeps the original's shape for source compatibility but not every knob is functional yet.

| Knob                                                       | Status | Notes                                                                                                                  |
|:-----------------------------------------------------------|:-------|:-----------------------------------------------------------------------------------------------------------------------|
| `serverConfiguration` (baseUrl, authenticator, httpLogger) | ⚠️     | A `baseUrl` with a path needs a trailing `/`. Without it the path is dropped, so `https://hapi.fhir.org/baseR4` sends requests to `/Patient`. android-fhir rejects such a URL at init. |
| `NetworkConfiguration` timeouts                            | ✅      | `writeTimeOut` maps to the socket timeout.                                                                             |
| `customSearchParameters`                                   | ✅      | See [Custom search parameters](conformance.md#custom-search-parameters).                                               |
| `storageDirectory`                                         | ✅      | Desktop and web only. See [Platform support](conformance.md#platform-support).                                         |
| `uploadWithGzip`                                           | ⚠️     | Works on Android and Desktop. iOS and web send uploads uncompressed and log a warning. See [Platform support](conformance.md#platform-support). |
| `httpCache`                                                | ⚠️     | Toggles Ktor's default in-memory cache. `CacheConfiguration.cacheDir` and `maxSize` are ignored.                       |
| `enableEncryptionIfSupported`                              | ⚠️     | Renamed `enableEncryption`. Android and iOS. Where it cannot encrypt it throws at init instead of storing plaintext as android-fhir does. See [Platform support](conformance.md#platform-support). |
| `databaseErrorStrategy`                                    | ✅      | `RECREATE_AT_OPEN` recreates an encrypted database the current key cannot open, as in android-fhir.                   |
| `testMode`                                                 | ✅      | In-memory database on every platform.                                                                                  |

## Sync errors

`ResourceSyncException` keeps only the exception message, where android-fhir keeps the exception.
The type and stack trace are lost, and a failure without a message reaches the app as
`Unknown Exception`. ⚠️

## Database

Same schema, file name and migrations as android-fhir. An app switching from android-fhir keeps its
data. ✅

Databases from releases 2.0.0-alpha01 to alpha04 are converted in place and keep their data. ✅

An app on android-fhir with encryption on can update to this engine. Its database opens with the
same Keystore key and keeps its data. ✅
