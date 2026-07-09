# :sqlite-wasm-worker

The Wasm SQLite backend for the engine's Room database. Only the engine's `wasmJsMain` depends on
it.

## Why it's needed

The native SQLite driver (`androidx.sqlite:sqlite-bundled`) used on Android/desktop/iOS has no Wasm
build. On Wasm, Room 3 instead runs SQLite-WASM in a Web Worker (persisted to
[OPFS](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API/Origin_private_file_system))
via `androidx.sqlite.driver.web.WebWorkerSQLiteDriver`.

This module packages that Wasm-only backend so its JS/npm stays out of the `:engine`
library:

- `worker.js` and `package.json`, the SQLite-WASM Web Worker and its npm
  dependency (`@sqlite.org/sqlite-wasm`).
- `createSqliteWasmDriver()` wires that worker to `WebWorkerSQLiteDriver`; the engine's Wasm
  `DatabaseBuilder` calls it via `.setDriver()`.

## Why Room 3

Wasm support requires Room 3, Room 2 has no Wasm target. Room 3 is a new
package (`androidx.room3.*`), so the engine's entities/DAOs import from `androidx.room3` on all
platforms.
See [Room for KMP](https://developer.android.com/kotlin/multiplatform/room) and the
[Room 3.0 release notes](https://developer.android.com/jetpack/androidx/releases/room3).

## Browser requirement

SQLite-WASM and OPFS need `SharedArrayBuffer`, which requires a
[cross-origin-isolated](https://web.dev/articles/coop-coep) page. The demo sets the required
`COOP`/`COEP` headers in `engine-app/webpack.config.d/coop-coep.js`.
