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
| `serverConfiguration` (baseUrl, authenticator, httpLogger) | ✅      |                                                                                                                        |
| `NetworkConfiguration` timeouts                            | ✅      | `writeTimeOut` maps to the socket timeout.                                                                             |
| `customSearchParameters`                                   | ✅      | See [Custom search parameters](conformance.md#custom-search-parameters).                                               |
| `storageDirectory`                                         | ✅      | Desktop and web only. See [Platform support](conformance.md#platform-support).                                         |
| `uploadWithGzip`                                           | ⚠️     | Works on Android and Desktop. Broken labeling on iOS and web. See [Platform support](conformance.md#platform-support). |
| `httpCache`                                                | ⚠️     | Toggles Ktor's default in-memory cache. `CacheConfiguration.cacheDir` and `maxSize` are ignored.                       |
| `enableEncryptionIfSupported`                              | ❌      | Throws `IllegalArgumentException`. Encryption is not yet implemented.                                                  |
| `databaseErrorStrategy`                                    | ❌      | Accepted but never read. `RECREATE_AT_OPEN` has no effect.                                                             |
| `testMode`                                                 | ❌      | Accepted but never read. There is no in-memory database path.                                                          |
