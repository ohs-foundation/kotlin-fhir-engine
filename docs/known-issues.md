# Known issues

What doesn't work in the KMP engine today. **Bugs** are things that should work and don't. Most
have a test that is ignored until it's fixed, listed in [Ignored tests](ignored-tests.md). **Not
supported yet** are features the KMP engine doesn't have.

## Bugs

### Search and indexing

- **Codes lose their system when indexed.** Coded values such as `gender` or `status` are stored
  without their code system, so a token search that gives a system finds nothing. Workaround:
  search by the code alone.
- **Quantities with a unit or code aren't indexed.** Searching `value-quantity` for `5.4 g` finds
  nothing. Only quantities without a unit or code are indexed.
- **An Observation with two or more components loses its component value parameters**
  (`component-value-quantity`, `component-value-concept`) and its `combo-value-*` parameters, which
  include the Observation's own value. FHIRPath's `as` fails on a list, so these aren't indexed for
  it at all.
- **Money currencies are indexed by name, such as `Eur` instead of `EUR`.** A search by currency
  code finds nothing.
- **UCUM units are converted with a trailing `1`,** so `g`, `kg` and `mg` become `g1` where
  android-fhir gives `g`. Quantity searches by unit code don't match a database android-fhir
  indexed.
- **A dateTime given as plain text isn't indexed,** such as `scheduledString`.
- **`eq` with a time never matches a `dateTime` field.** Workaround: search a range with `ge` and
  `lt`.
- **A `dateTime` with fractional seconds in a searchable field,** such as `2013-03-14T10:00:00.123Z`,
  makes `create`, `update` and sync downloads fail with `ArithmeticException`.
- **A token search with an empty system throws** a SQLite error.

### Sync

- **`FhirEngine.getLastSyncTimeStamp()` always returns `null`.** Workaround on Android:
  `Sync.getLastSyncTimestamp(context)` returns the saved time. Other platforms have none yet.
- **Every resource the server returns is changed before it's stored.** A step meant to repair
  invalid server data runs on every response. It drops narratives (`text`), and cuts the time off
  any field whose name ends in `Date`, such as `Condition.recordedDate`.
- **A base URL without a trailing `/` loses its path.** With `https://hapi.fhir.org/baseR4`,
  requests go to `https://hapi.fhir.org/Patient`. Workaround: end the base URL with `/`.
- **A sync error keeps only the exception message.** The exception's type and stack trace are
  lost, and a failure without a message reaches the app as `Unknown Exception`.

## Not supported yet

### Search

- **Search parameter types:** composite parameters, and `near` on `Location.position`.
- **Modifiers:** only `:exact` and `:contains` on strings. No `:missing`, `:not`, `:text`,
  `:above`, `:below`, `:in`, `:not-in`, `:of-type`, `:identifier` or `:[type]`, such as
  `subject:Patient`.
- **String matching** ignores case only for the letters A to Z, and doesn't ignore accents.
- **Tokens:** the `system|` form. The `|code` form throws, because of the empty system bug above.
- **References** match the stored text exactly. A full URL, a bare id or a versioned reference
  doesn't match `Patient/123`.
- **Sorting:** one field only, and only on string, number and date parameters.
- **Related resources:** chained parameters, and `:iterate` on `include` and `revInclude`.
- **Result parameters:** `_text`, `_content`, `_list`, `_summary`, `_elements`, `_contained`.
- **X-FHIR-Query strings:** no prefixes, modifiers, comma-separated values, repeated parameters,
  `include`, `revInclude` or `has`, and `_sort` uses only its last field. Use the search DSL for
  those.

### Sync

- **Updates upload as `PATCH` only.** `HttpUpdateMethod.PUT` throws `NotImplementedError`, as in
  android-fhir.
- **`If-Match` is sent on bundle uploads only,** not on individual requests.
- **Conflict resolution** has one outcome, resolved. There's no abort or defer.

### Platforms

| Feature | Android | Desktop | iOS | JS and wasm |
|---|---|---|---|---|
| Database encryption | ✅ | Throws at init | ✅ The app links SQLCipher, or init throws | Throws at init |
| Gzip uploads (`uploadWithGzip`) | ✅ | ✅ | Sent uncompressed, with a warning | Sent uncompressed, with a warning |
| Gzip downloads | ✅ | Only when `uploadWithGzip` is on | ✅ | ✅ |
| Built-in sync scheduling | ✅ WorkManager | The app schedules it | The app schedules it | The app schedules it |

On web, the database worker logs every message it handles to the browser console, including the
SQL and its values, so patient data can appear there.

`httpCache` turns on Ktor's in-memory cache. Its `cacheDir` and `maxSize` are ignored.
