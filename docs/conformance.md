# Conformance

This page documents the conformance of the Kotlin FHIR Engine against the official HL7 FHIR
specifications it implements. The library targets **FHIR R4 (v4.0.1)**. The Search DSL is measured
against the [FHIR R4 Search specification](https://hl7.org/fhir/R4/search.html) and synchronization
is measured against the [FHIR R4 RESTful API specification](https://hl7.org/fhir/R4/http.html).
This page also documents per platform support. Parity with the original android-fhir engine is
documented separately in [android-fhir parity](android-fhir-parity.md).

## Table of Contents

- [Status legend](#status-legend)
- [FHIR Search specification](#fhir-search-specification)
  - [Search parameter types](#search-parameter-types)
  - [Prefixes](#prefixes)
  - [Modifiers](#modifiers)
  - [Search result parameters](#search-result-parameters)
  - [X-FHIR-Query](#x-fhir-query)
  - [Custom search parameters](#custom-search-parameters)
- [FHIR RESTful API](#fhir-restful-api)
  - [HTTP interactions](#http-interactions)
  - [Download](#download)
  - [Concurrency with ETags](#concurrency-with-etags)
  - [Conflict resolution](#conflict-resolution)
- [Platform support](#platform-support)

## Status legend

- ✅ Fully supported and conforms to the specification.
- ⚠️ Partially implemented or diverges from the specification. See *Notes*.
- ❌ Unimplemented.

## FHIR Search specification

The Search DSL executes FHIR search semantics locally against the on-device database. This section
documents its conformance against the
[FHIR R4 (v4.0.1) Search specification](https://hl7.org/fhir/R4/search.html).

### Search parameter types

This table documents the parameter types defined in
[sec. 3.1.1.4](https://hl7.org/fhir/R4/search.html#ptypes). Each supported type has a typed
`filter(...)` overload in
[`BaseSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/BaseSearch.kt) and an
index table populated by
[`ResourceIndexer.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/index/ResourceIndexer.kt).

| Type             | Specification                                              | Code                                                                                                                                     | Status | Notes                                                                                                                                                                                                |
|:-----------------|:-----------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------|:-------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| number           | [number](https://hl7.org/fhir/R4/search.html#number)       | [`NumberParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/NumberParamFilterCriterion.kt)       | ✅      | `eq` uses the value's implicit precision range per spec.                                                                                                                                             |
| date / dateTime  | [date](https://hl7.org/fhir/R4/search.html#date)           | [`DateParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/DateParamFilterCriterion.kt)           | ✅      | Range based with precision widening. Indexes `Date`, `DateTime`, `Instant`, `Period` and `Timing`.                                                                                                   |
| string           | [string](https://hl7.org/fhir/R4/search.html#string)       | [`StringParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/StringParamFilterCriterion.kt)       | ⚠️     | Default matches starts with, case insensitive. Accent insensitivity required by the spec is not implemented.                                                                                         |
| token            | [token](https://hl7.org/fhir/R4/search.html#token)         | [`TokenParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/TokenParamFilterCriterion.kt)         | ⚠️     | Matches code and, when given, system. Accepts `Boolean`, `String`, `Uri`, `Code`, `Coding`, `CodeableConcept`, `Identifier` and `ContactPoint`. The `system\|` and `\|code` forms are not supported. |
| reference        | [reference](https://hl7.org/fhir/R4/search.html#reference) | [`ReferenceParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/ReferenceParamFilterCriterion.kt) | ⚠️     | Exact match on the stored reference string such as `Patient/123`. Full URL, bare id and versioned forms are not normalized.                                                                          |
| composite        | [composite](https://hl7.org/fhir/R4/search.html#composite) |                                                                                                                                          | ❌      | Not indexed and not filterable.                                                                                                                                                                      |
| quantity         | [quantity](https://hl7.org/fhir/R4/search.html#quantity)   | [`QuantityParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/QuantityParamFilterCriterion.kt)   | ✅      | UCUM units are canonicalized on both index and query sides, so `1 m` matches `100 cm`. See [`UcumValue.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/UcumValue.kt).                       |
| uri              | [uri](https://hl7.org/fhir/R4/search.html#uri)             | [`UriParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/UriParamFilterCriterion.kt)             | ⚠️     | Exact match only.                                                                                                                                                                                    |
| special (`near`) | [special](https://hl7.org/fhir/R4/search.html#special)     | [`ResourceIndexer.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/index/ResourceIndexer.kt)                                     | ❌      | `Location.position` is indexed but there is no filter to query it.                                                                                                                                   |

### Prefixes

This table documents the prefixes defined in
[sec. 3.1.1.4.1](https://hl7.org/fhir/R4/search.html#prefix). Prefixes apply to number, date and
quantity filters. Comparison logic lives in
[`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt).

| Prefix                    | Specification                                        | Code                                                                                        | Status | Notes                                                                                 |
|:--------------------------|:-----------------------------------------------------|:--------------------------------------------------------------------------------------------|:-------|:--------------------------------------------------------------------------------------|
| `eq` (default)            | [prefix](https://hl7.org/fhir/R4/search.html#prefix) | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt) | ✅      |                                                                                       |
| `ne`                      | [prefix](https://hl7.org/fhir/R4/search.html#prefix) | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt) | ✅      |                                                                                       |
| `gt` / `ge` / `lt` / `le` | [prefix](https://hl7.org/fhir/R4/search.html#prefix) | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt) | ✅      |                                                                                       |
| `sa` / `eb`               | [prefix](https://hl7.org/fhir/R4/search.html#prefix) | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt) | ✅      | Rejected for integer values per spec.                                                 |
| `ap`                      | [prefix](https://hl7.org/fhir/R4/search.html#prefix) | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt) | ✅      | Numbers use ±10% of the value. Dates widen the range by 10% of the distance from now. |

### Modifiers

This table documents the modifiers defined in
[sec. 3.1.1.4.2](https://hl7.org/fhir/R4/search.html#modifiers). Only string modifiers exist, as
`StringFilterModifier` in
[`Search.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/Search.kt).

| Modifier                         | Specification                                              | Code                                                                                                                               | Status | Notes                                                                            |
|:---------------------------------|:-----------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------|:-------|:---------------------------------------------------------------------------------|
| `:exact` (string)                | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) | [`StringParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/StringParamFilterCriterion.kt) | ✅      | As `StringFilterModifier.MATCHES_EXACTLY`.                                       |
| `:contains` (string)             | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) | [`StringParamFilterCriterion.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/filter/StringParamFilterCriterion.kt) | ⚠️     | As `StringFilterModifier.CONTAINS`. Case insensitive but not accent insensitive. |
| `:missing`                       | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      |                                                                                  |
| `:text` (token)                  | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      |                                                                                  |
| `:not` (token)                   | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      |                                                                                  |
| `:above` / `:below` (token, uri) | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      |                                                                                  |
| `:in` / `:not-in` (token)        | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      | Requires terminology support.                                                    |
| `:of-type` (token)               | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      |                                                                                  |
| `:identifier` (reference)        | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      |                                                                                  |
| `:[type]` (reference)            | [modifiers](https://hl7.org/fhir/R4/search.html#modifiers) |                                                                                                                                    | ❌      | The type is written in the filter value instead, such as `Patient/123`.          |

### Search result parameters

This table documents the result parameters defined in
[sec. 3.1.1.5 and 3.1.1.6](https://hl7.org/fhir/R4/search.html#return). Implemented in
[`Search.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/Search.kt),
[`NestedSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/NestedSearch.kt) and
[`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt).

| Parameter                       | Specification                                                | Code                                                                                                    | Status | Notes                                                                                                         |
|:--------------------------------|:-------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------|:-------|:--------------------------------------------------------------------------------------------------------------|
| `_sort`                         | [sort](https://hl7.org/fhir/R4/search.html#sort)             | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt)             | ⚠️     | Single sort field only. String, number and date fields are sortable. Other types throw `NotImplementedError`. |
| `_count` and offset             | [count](https://hl7.org/fhir/R4/search.html#count)           | [`MoreSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreSearch.kt)             | ✅      | `Search.count` and `Search.from`.                                                                             |
| `_include`                      | [include](https://hl7.org/fhir/R4/search.html#include)       | [`NestedSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/NestedSearch.kt)         | ⚠️     | No `:iterate` and no count on the included set.                                                               |
| `_revinclude`                   | [revinclude](https://hl7.org/fhir/R4/search.html#revinclude) | [`NestedSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/NestedSearch.kt)         | ⚠️     | Same limitations as `_include`.                                                                               |
| `_has`                          | [has](https://hl7.org/fhir/R4/search.html#has)               | [`NestedSearch.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/NestedSearch.kt)         | ✅      | Supports multiple depths.                                                                                     |
| Chained parameters              | [chaining](https://hl7.org/fhir/R4/search.html#chaining)     |                                                                                                         | ❌      | Only reverse chaining via `has` is available.                                                                 |
| `_id`                           | [all resources](https://hl7.org/fhir/R4/search.html#all)     |                                                                                                         | ⚠️     | Query via `TokenClientParam("_id")`. No dedicated helper.                                                     |
| `_lastUpdated`                  | [all resources](https://hl7.org/fhir/R4/search.html#all)     | [`MoreClientParams.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/MoreClientParams.kt) | ⚠️     | Query via `DateClientParam("_lastUpdated")`. No dedicated helper.                                             |
| `_tag` / `_security`            | [all resources](https://hl7.org/fhir/R4/search.html#all)     |                                                                                                         | ⚠️     | Indexed as token parameters. No dedicated helpers.                                                            |
| `_profile` / `_source`          | [all resources](https://hl7.org/fhir/R4/search.html#all)     |                                                                                                         | ⚠️     | Indexed as uri parameters. No dedicated helpers.                                                              |
| `_total`                        | [total](https://hl7.org/fhir/R4/search.html#total)           | [`FhirEngine.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/FhirEngine.kt)                    | ⚠️     | Use `FhirEngine.count(search)` instead.                                                                       |
| `_text` / `_content`            | [text search](https://hl7.org/fhir/R4/search.html#text)      |                                                                                                         | ❌      | No full text search.                                                                                          |
| `_list`                         | [list](https://hl7.org/fhir/R4/search.html#list)             |                                                                                                         | ❌      |                                                                                                               |
| `_summary` / `_elements`        | [summary](https://hl7.org/fhir/R4/search.html#summary)       |                                                                                                         | ❌      | Whole resources are always returned.                                                                          |
| `_contained` / `_containedType` | [contained](https://hl7.org/fhir/R4/search.html#contained)   |                                                                                                         | ❌      |                                                                                                               |

### X-FHIR-Query

[`XFhirQueryTranslator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/query/XFhirQueryTranslator.kt)
translates an x-fhir-query string such as `Patient?family=Smith&_sort=-name&_count=10` into a
`Search`, exposed as `FhirEngine.search(xFhirQuery)`. It supports a subset of the DSL.

| Feature                                                              | Code                                                                                                                  | Status | Notes                                                                                           |
|:---------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------|:-------|:------------------------------------------------------------------------------------------------|
| Filters for number, date, string, token, reference, uri and quantity | [`XFhirQueryTranslator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/query/XFhirQueryTranslator.kt) | ✅      | Token as `system\|code` or `code`. Quantity as `value\|system\|unit`, `value\|unit` or `value`. |
| `_sort` with `-` for descending                                      | [`XFhirQueryTranslator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/query/XFhirQueryTranslator.kt) | ⚠️     | Only the last field takes effect.                                                               |
| `_count`                                                             | [`XFhirQueryTranslator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/search/query/XFhirQueryTranslator.kt) | ✅      | No offset parameter.                                                                            |
| Prefixes such as `gt2010-01-01`                                      |                                                                                                                       | ❌      |                                                                                                 |
| Modifiers such as `name:exact`                                       |                                                                                                                       | ❌      |                                                                                                 |
| Chained parameters and FHIRPath expressions                          |                                                                                                                       | ❌      |                                                                                                 |
| `_include` / `_revinclude` / `_has`                                  |                                                                                                                       | ❌      | Use the Search DSL for these.                                                                   |
| Composite and special parameters                                     |                                                                                                                       | ❌      | Rejected with `UnsupportedOperationException`.                                                  |

### Custom search parameters

`FhirEngineConfiguration.customSearchParameters` registers additional
[`SearchParamDefinition`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/index/SearchParamDefinition.kt)s.
They are indexed and queryable like built-in parameters, including from x-fhir-query. ✅

The definition's path must start with the resource type, such as `Patient.extension.where(...)`.
The path must be a valid FHIRPath expression. Resources indexed before a custom parameter was
registered are not re-indexed automatically. Update them to re-index.

## FHIR RESTful API

Synchronization implements the client side of the
[FHIR R4 (v4.0.1) RESTful API](https://hl7.org/fhir/R4/http.html). It downloads changed resources
from the server and then uploads local changes. The supported upload strategy configurations are
documented in the [README](../README.md#supported-upload-strategies).

### HTTP interactions

Transport is
[`KtorHttpService.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/remote/KtorHttpService.kt)
and dispatch is
[`FhirHttpDataSource.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/remote/FhirHttpDataSource.kt).

| Interaction                     | Specification                                                | Code                                                                                                                                                       | Status | Notes                                                                        |
|:--------------------------------|:-------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------|:-------|:-----------------------------------------------------------------------------|
| read and search (GET)           | [read](https://hl7.org/fhir/R4/http.html#read)               | [`KtorHttpService.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/remote/KtorHttpService.kt)                                                 | ✅      |                                                                              |
| create (POST or PUT)            | [create](https://hl7.org/fhir/R4/http.html#create)           | [`KtorHttpService.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/remote/KtorHttpService.kt)                                                 | ✅      | Verb selected by `UploadStrategy.methodForCreate`.                           |
| update (PUT)                    | [update](https://hl7.org/fhir/R4/http.html#update)           |                                                                                                                                                            | ❌      | Updates are sent as PATCH only. PUT for update throws `NotImplementedError`. |
| patch (PATCH)                   | [patch](https://hl7.org/fhir/R4/http.html#patch)             | [`KtorHttpService.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/remote/KtorHttpService.kt)                                                 | ✅      | JSON Patch with `Content-Type` `application/json-patch+json`.                |
| delete (DELETE)                 | [delete](https://hl7.org/fhir/R4/http.html#delete)           | [`KtorHttpService.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/remote/KtorHttpService.kt)                                                 | ✅      |                                                                              |
| transaction (POST Bundle)       | [transaction](https://hl7.org/fhir/R4/http.html#transaction) | [`TransactionBundleGenerator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/upload/request/TransactionBundleGenerator.kt)                   | ✅      | Resources that reference each other cyclically are kept in the same bundle.  |
| paging with Bundle `next` links | [paging](https://hl7.org/fhir/R4/http.html#paging)           | [`ResourceParamsBasedDownloadWorkManager.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/download/ResourceParamsBasedDownloadWorkManager.kt) | ✅      |                                                                              |

### Download

[`ResourceParamsBasedDownloadWorkManager.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/download/ResourceParamsBasedDownloadWorkManager.kt)
drives downloads. A fully custom
[`DownloadWorkManager`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/DownloadWorkManager.kt)
can replace it.

| Feature                                                                         | Status | Notes                                                              |
|:--------------------------------------------------------------------------------|:-------|:-------------------------------------------------------------------|
| Incremental download with `_lastUpdated=gt<timestamp>` and `_sort=_lastUpdated` | ✅      | Watermark persisted per resource type.                             |
| Progress totals via `_summary=count`                                            | ✅      |                                                                    |
| Follows Bundle `next` links                                                     | ✅      |                                                                    |
| `OperationOutcome` error detection                                              | ✅      | Throws on error responses. Only `searchset` bundles are processed. |

### Concurrency with ETags

The specification section is
[Managing Resource Contention](https://hl7.org/fhir/R4/http.html#concurrency).

| Feature                           | Code                                                                                                                                           | Status | Notes                                                                                                                                                 |
|:----------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------|:-------|:------------------------------------------------------------------------------------------------------------------------------------------------------|
| `If-Match` on bundle uploads      | [`BundleEntryComponentGenerator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/upload/request/BundleEntryComponentGenerator.kt) | ✅      | On by default for update and delete entries.                                                                                                          |
| `If-Match` on individual uploads  |                                                                                                                                                | ❌      | [`UrlRequestGenerator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/upload/request/UrlRequestGenerator.kt) sets no `If-Match` header. |
| Server ETag captured after upload | [`ResourceConsolidator.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/upload/ResourceConsolidator.kt)                           | ✅      | Version and lastUpdated stored locally.                                                                                                               |

### Conflict resolution

[`ConflictResolver.kt`](../engine/src/commonMain/kotlin/dev/ohs/fhir/engine/sync/ConflictResolver.kt)
provides `AcceptLocalConflictResolver` and `AcceptRemoteConflictResolver`, or implement the
`ConflictResolver` interface. ✅ The only resolution outcome is `Resolved`. There is no abort or
defer outcome.

## Platform support

| Feature                                           | Android        | Desktop (JVM) | iOS            | js / wasmJs    | Notes                                                                                                                                                                                                                                              |
|:--------------------------------------------------|:---------------|:--------------|:---------------|:---------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| CRUD, Search DSL, local changes                   | ✅              | ✅             | ✅              | ✅              | Room 3 with the bundled native SQLite driver. Web uses a SQLite WASM Web Worker with OPFS persistence. See the [README](../README.md) for the required web setup.                                                                                  |
| Sync engine (`FhirSyncTask`, download and upload) | ✅              | ✅             | ✅              | ✅              | Platform neutral.                                                                                                                                                                                                                                  |
| OS level sync scheduler in the library            | ✅ WorkManager  | ❌             | ❌              | ❌              | Other platforms must schedule sync themselves. The demo app shows an iOS BGTask scheduler and a foreground scheduler for desktop and web.                                                                                                          |
| `uploadWithGzip`                                  | ✅              | ✅             | ⚠️             | ⚠️             | Ktor compresses request bodies only on the JVM. On iOS and web the body is labeled gzip but sent uncompressed and servers reject it. Keep the flag off on those platforms. Tracked as [KTOR-8198](https://youtrack.jetbrains.com/issue/KTOR-8198). |
| Response decompression on downloads               | ✅              | ⚠️            | ✅              | ✅              | Handled by OkHttp, NSURLSession and the browser. The Desktop engine only negotiates gzip when `uploadWithGzip` is enabled.                                                                                                                         |
| `storageDirectory` honored                        | ❌ by design    | ✅             | ❌ by design    | ✅              | Android and iOS use OS provided app scoped storage. Web namespaces the database and preferences.                                                                                                                                                   |
| Database encryption                               | ❌              | ❌             | ❌              | ❌              | Not yet implemented. `enableEncryptionIfSupported = true` throws instead of silently storing plaintext.                                                                                                                                            |
| Schema migrations                                 | ⚠️             | ⚠️            | ⚠️             | ⚠️             | Alpha policy. Schema changes recreate the database and all local data is lost, so sync first. Schema history is exported for future migrations.                                                                                                    |
| Tests executed in CI                              | ❌ compile only | ✅             | ❌ compile only | ❌ compile only | As of this revision CI runs `:engine:desktopTest`.                                                                                                                                                                                                 |
