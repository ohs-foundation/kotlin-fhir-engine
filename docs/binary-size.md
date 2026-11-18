# Binary size

CI measures the size of every published module on each platform. On a pull request it builds the
base and the head in one job and comments with the difference.

## Run it locally

```bash
./gradlew --init-script .github/scripts/binary-size.init.gradle binarySizeArtifacts
python3 .github/scripts/binary_size.py measure --root . --output head.json
python3 .github/scripts/binary_size.py render head.json
```

To compare with another commit, build it in a second checkout with the same init script, measure
it, and pass `--base base.json` to `render`.

The D8 and R8 metrics need the Android SDK build tools. Pass `--skip-android` to `measure` without
them.

## Metrics

| Metric | What it is |
|---|---|
| Android: R8 release DEX incl. dependencies | The module and its runtime dependencies shrunk with R8, keeping the module's whole public API. An upper bound for an app that uses all of it. |
| Android: native libraries (ABI) | `.so` files from the module and its dependencies, per ABI. Bundled SQLite and SQLCipher. |
| Android: assets incl. dependencies | `assets/` from the module and its dependencies. |
| Android: AAR | The published AAR. |
| Android: D8 DEX (module only) | The module's classes as unshrunk DEX, and their method count. |
| JVM (desktop): JAR | The published desktop JAR. |
| JVM (desktop): native libraries (platform) | Native libraries in the runtime dependencies, per OS and architecture. Bundled SQLite ships all of them. |
| JS: total (gzip) | The development library output with every linked Kotlin dependency. |
| JS: module only (gzip) | The module's own file in that output. |
| Wasm: total (gzip) | The development library output. Wasm links everything into one binary. |
| Web: SQLite Wasm worker (gzip) | The worker and the `@sqlite.org/sqlite-wasm` files the browser loads. |
| Runtime dependencies (target) | The resolved runtime classpath, before shrinking. |

JS and Wasm use the development build because the production build removes code that the module
does not export. iOS is not measured yet.

Changes under 1 KB and under 0.1% are shown as unchanged, as builds are not byte-for-byte
reproducible.

## Files

| File | Role |
|---|---|
| `.github/scripts/binary-size.init.gradle` | Adds `binarySizeArtifacts` to published modules and writes `build/binary-size/manifest.json`. |
| `.github/scripts/binary_size.py` | Measures the manifests and renders the table. |
| `.github/workflows/ci.yml` (`binary-size`) | Builds and measures the base and the head. |
| `.github/workflows/binary-size-comment.yml` | Posts the table on the pull request. |

Tests:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_binary_size.py'
```
