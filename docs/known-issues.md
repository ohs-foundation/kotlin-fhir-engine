# Known issues

What doesn't work in the KMP engine today. **Bugs** are things that should work and don't. Most
have a test that is ignored until it's fixed, listed in [Ignored tests](ignored-tests.md). **Not
supported yet** are features the KMP engine doesn't have.

## Bugs

| Area | Bug | Workaround | Issue |
|---|---|---|---|
| Search | Coded values such as `gender` or `status` are indexed without their code system, so a token search that gives a system finds nothing. | Search by the code alone. | [#125](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/125) |
| Search | Quantities with a unit or code aren't indexed, so searching `value-quantity` for `5.4 g` finds nothing. Only quantities without a unit or code are indexed. | None | [#126](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/126) |
| Search | An Observation with two or more components loses its `component-value-*` and `combo-value-*` parameters, which include the Observation's own value. FHIRPath's `as` fails on a list, so these aren't indexed for it at all. | None | [#127](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/127) |
| Search | Money currencies are indexed by name, such as `Eur` instead of `EUR`, so a search by currency code finds nothing. | None | [#133](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/133) |
| Search | UCUM units are converted with a trailing `1`, so `g`, `kg` and `mg` become `g1` where android-fhir gives `g`. Quantity searches by unit code don't match a database android-fhir indexed. | None | [#135](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/135) |
| Search | A dateTime given as plain text isn't indexed, such as `scheduledString`. | None | [#134](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/134) |
| Search | A `dateTime` with fractional seconds in a searchable field, such as `2013-03-14T10:00:00.123Z`, makes `create`, `update` and sync downloads fail with `ArithmeticException`. | None | [#124](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/124) |
| Search | A token search with an empty system throws a SQLite error. | None | [#129](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/129) |
| Sync | `FhirEngine.getLastSyncTimeStamp()` always returns `null`. | On Android, `Sync.getLastSyncTimestamp(context)` returns the saved time. Other platforms have none yet. |  |
| Sync | Every resource the server returns is changed before it's stored. A step meant to repair invalid server data runs on every response. It drops narratives (`text`), and cuts the time off any field whose name ends in `Date`, such as `Condition.recordedDate`. | None | [#128](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/128) |
| Sync | A base URL without a trailing `/` loses its path. With `https://hapi.fhir.org/baseR4`, requests go to `https://hapi.fhir.org/Patient`. | End the base URL with `/`. | [#90](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/90) |
| Sync | A sync error keeps only the exception message. The exception's type and stack trace are lost, and a failure without a message reaches the app as `Unknown Exception`. | None | [#91](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/91) |
| Sync | `httpCache` keeps an unlimited cache in memory. `cacheDir` and `maxSize` are ignored, where android-fhir keeps a disk cache of `maxSize`. | None | [#131](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/131) |

## Not supported yet

| Area | Not supported |
|---|---|
| Search | Search parameter types: composite parameters, and `near` on `Location.position`. |
| Search | Modifiers other than `:exact` and `:contains` on strings. No `:missing`, `:not`, `:text`, `:above`, `:below`, `:in`, `:not-in`, `:of-type`, `:identifier` or `:[type]`, such as `subject:Patient`. |
| Search | String matching ignores case only for the letters A to Z, and doesn't ignore accents. |
| Search | The `system\|` token form. The `\|code` form throws, because of the empty system bug above. |
| Search | References match the stored text exactly. A full URL, a bare id or a versioned reference doesn't match `Patient/123`. |
| Search | Sorting by more than one field, or on parameters other than string, number and date. |
| Search | Chained parameters, and `:iterate` on `include` and `revInclude`. |
| Search | The result parameters `_text`, `_content`, `_list`, `_summary`, `_elements` and `_contained`. |
| Search | In X-FHIR-Query strings: prefixes, modifiers, comma-separated values, repeated parameters, `include`, `revInclude` and `has`. `_sort` uses only its last field. Use the search DSL for those. |
| Sync | Updates upload as `PATCH` only. `HttpUpdateMethod.PUT` throws `NotImplementedError`, as in android-fhir. See [#136](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/136). |
| Sync | `If-Match` on individual requests. It's sent on bundle uploads only. |
| Sync | Conflict resolution outcomes other than resolved. There's no abort or defer. |
| Sync | Discarding the local changes of a resource and going back to the server version. `purge` with `forcePurge = true` deletes the resource instead. See [#137](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/137). |
| Sync | Resource types that stay on the device and never upload. Every `create`, `update` and `delete` is uploaded at the next sync. See [#138](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/138). |
| Server | Working directly with the server without a local database. The server is reached only through sync. See [#139](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/139). |

## Platforms

| Feature | Android | Desktop | iOS | JS and wasm |
|---|---|---|---|---|
| Database encryption | ✅ | Throws at init | ✅ The app links SQLCipher, or init throws | Throws at init |
| Gzip uploads (`uploadWithGzip`) | ✅ | ✅ | Sent uncompressed, with a warning | Sent uncompressed, with a warning |
| Gzip downloads | ✅ | Only when `uploadWithGzip` is on | ✅ | ✅ |
| Built-in sync scheduling | ✅ WorkManager | The app schedules it | The app schedules it | The app schedules it |
| Closing the database (`FhirEngineProvider.cleanup()`) | ✅ | ✅ | ✅ | Leaves it open, because a close can freeze the browser. The cause isn't known yet. See [#132](https://github.com/ohs-foundation/kotlin-fhir-engine/issues/132). |
