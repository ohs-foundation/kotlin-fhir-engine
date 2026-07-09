# Kotlin FHIR Engine

A Kotlin Multiplatform library for building offline-capable healthcare applications using the HL7 FHIR R4 standard. Provides FHIR resource persistence, synchronization with remote FHIR servers, and a type-safe search API.

## Supported Platforms

- Android
- iOS
- Desktop (JVM)
- Web (Wasm)

## Web (Wasm)

Room 3 is required to support Wasm, Room 2 has no Wasm target. This is why
the engine uses `androidx.room3.*` on all platforms.
See [Room for KMP](https://developer.android.com/kotlin/multiplatform/room) and the
[Room 3.0 release notes](https://developer.android.com/jetpack/androidx/releases/room3).

Android, iOS, and Desktop use the bundled native SQLite driver (`BundledSQLiteDriver` from
`sqlite-bundled`), which has no Wasm build. So on Wasm the database instead uses a different driver,
`WebWorkerSQLiteDriver`, backed by a SQLite-WASM Web Worker. That worker and its driver live in the
`:sqlite-wasm-worker` module, see its [README](sqlite-wasm-worker/README.md).

## License

Licensed under the Apache License, Version 2.0.
