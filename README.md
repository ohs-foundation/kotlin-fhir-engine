# Kotlin FHIR Engine

[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine?color=yellow&label=fhir-engine)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-android?color=yellow&label=android)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-android)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-desktop?color=yellow&label=desktop)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-desktop)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-wasm-js?color=yellow&label=wasm-js)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-wasm-js)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-iossimulatorarm64?color=yellow&label=iossimulatorarm64)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-iossimulatorarm64)
[![Release](https://img.shields.io/maven-central/v/dev.ohs.fhir/fhir-engine-iosarm64?color=yellow&label=iosarm64)](https://central.sonatype.com/artifact/dev.ohs.fhir/fhir-engine-iosarm64)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A Kotlin Multiplatform library for building offline-capable healthcare applications using the HL7
FHIR R4 standard. It provides on-device FHIR resource persistence, a type-safe search API, and
synchronization with remote FHIR servers.

## Supported platforms

The library's support for different
[target platforms](https://kotlinlang.org/docs/multiplatform-dsl-reference.html#targets)
is listed in the following table:

| Target platform                    | Gradle target       | Artifact suffix      | Support |
|:-----------------------------------|:--------------------|:---------------------|:--------|
| Kotlin/JVM (Desktop)               | `jvm("desktop")`    | `-desktop`           | ✅       |
| Kotlin/Wasm                        | `wasmJs`            | `-wasm-js`           | ✅       |
| Android applications and libraries | `android`           | `-android`           | ✅       |
| iOS (Apple silicon simulator)      | `iosSimulatorArm64` | `-iossimulatorarm64` | ✅       |
| iOS (device)                       | `iosArm64`          | `-iosarm64`          | ✅       |

## Demo app

The `engine-app` module is a multiplatform demo application (Android, Desktop, iOS, and Web). Run it
with:

```bash
./gradlew :engine-app:run                              # Desktop
./gradlew :engine-app:wasmJsBrowserDevelopmentRun      # Web
./gradlew :engine-app:installDebug                     # Android
```

For iOS, build the framework and run the demo from Xcode on a simulator.

## User guide

### Adding the library dependency to your project

To use the Kotlin FHIR Engine library in your project, you need to add the library dependency to your
project. To do that, first make sure to include the `mavenCentral()`[^1] repository in the
`build.gradle.kts` file in your project root.

[^1]: Early versions of this library were published under the group ID `com.google.android.fhir` and
artifact ID `engine` on [Google Maven](https://maven.google.com/web/index.html#com.google.android.fhir:engine).

```
// build.gradle.kts
repositories {
    // Other repositories such as gradlePluginPortal() and google()
    mavenCentral()
}
```

Next, follow the instructions for your specific project type.

#### Kotlin Multiplatform Projects

For Kotlin Multiplatform projects, add the dependency to the shared `commonMain` source set within
the `kotlin` block of the module's `build.gradle.kts` file (e.g., `composeApp/build.gradle.kts` or
`shared/build.gradle.kts`). This makes the library available across all platforms in your project.

```
// e.g., composeApp/build.gradle.kts or shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.ohs.fhir:fhir-engine:2.0.0-alpha01")
        }
    }
}
```

#### Android projects

For Android projects, add the dependency to the `dependencies` block in the module's
`build.gradle.kts` file (e.g., `app/build.gradle.kts`).

```
// e.g., app/build.gradle.kts
dependencies {
    implementation("dev.ohs.fhir:fhir-engine:2.0.0-alpha01")
}
```

### Persisting and searching resources

Initialize `FhirEngineProvider` once at startup, then use the `FhirEngine` instance for CRUD and
search. On Android pass the application `Context`; on other platforms pass `Unit` (the default).

```kotlin
FhirEngineProvider.init(
    FhirEngineConfiguration(
        // Optional: a remote server to sync against, and custom search parameters.
        serverConfiguration = ServerConfiguration(baseUrl = "https://hapi.fhir.org/baseR4/"),
    ),
    platformContext, // Android: applicationContext; other platforms: Unit
)

val fhirEngine = FhirEngineProvider.getInstance(platformContext)

// Create (returns the assigned ids)
val ids = fhirEngine.create(Patient(id = "patient-1"))

// Read
val patient = fhirEngine.get(ResourceType.Patient, "patient-1") as Patient

// Update / delete
fhirEngine.update(patient)
fhirEngine.delete(ResourceType.Patient, "patient-1")

// Search (an empty block matches all; add filters/sort inside the block)
val patients = fhirEngine.search<Patient> {}
```

### Web (Wasm)

Persistence uses [Room](https://developer.android.com/kotlin/multiplatform/room). Web support
requires **Room 3** (`androidx.room3`), Room 2 has no Wasm target, which is why the engine uses
`androidx.room3.*` on all platforms.

Android, iOS, and Desktop use the bundled native SQLite driver (`BundledSQLiteDriver` from
`sqlite-bundled`), which has no Wasm build. On Wasm the database instead uses `WebWorkerSQLiteDriver`,
backed by a SQLite-WASM Web Worker. That worker and its driver live in the `:sqlite-wasm-worker`
module, see its [README](sqlite-wasm-worker/README.md).

## Developer guide

### Publishing

To publish a new release, first update `mavenVersion` in `gradle.properties` to the new version. Then
follow one of the methods below.

#### Maven Local

To publish artifacts to your local Maven repository (`~/.m2/repository`) for local development and
testing, run:

```bash
./gradlew :engine:publishToMavenLocal
```

#### Maven Central

Publishing to Maven Central requires two sets of credentials:

1. Maven Central credentials: your Sonatype portal username and password tokens.
2. GPG signing: a GPG key and its passphrase, used to sign all published artifacts.

See the
[Kotlin Multiplatform Publishing Guide](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries-to-maven.html)
and the
[Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-portal-guide/) for
more information on how to set up these credentials.

##### Publishing to Maven Central manually

For manual publishing, store the credentials in the global `~/.gradle/gradle.properties` in your
environment (not the project's `gradle.properties`) so they are never committed to the repository:

```properties
# Maven Central Credentials
mavenCentralUsername=YOUR_USERNAME_TOKEN
mavenCentralPassword=YOUR_PASSWORD_TOKEN

# GPG Signing (file-based)
signing.keyId=YOUR_KEY_ID
signing.password=YOUR_KEY_PASSWORD
signing.secretKeyRingFile=/path/to/secring.gpg
```

Then run:

```bash
./gradlew :engine:publishToMavenCentral
```

##### Publishing to Maven Central using GitHub Actions

The project includes a GitHub Actions [workflow](.github/workflows/publish.yml) that publishes to
Maven Central when a new GitHub release (or pre-release) is created.

The workflow requires the following GitHub organization or repository secrets (already set up):

| Secret                   | Description                                                                           |
|:-------------------------|:--------------------------------------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME` | Same as `mavenCentralUsername`                                                        |
| `MAVEN_CENTRAL_PASSWORD` | Same as `mavenCentralPassword`                                                        |
| `GPG_KEY_CONTENTS`       | Needs to be exported using the command `gpg --armor --export-secret-keys YOUR_KEY_ID` |
| `SIGNING_PASSWORD`       | Same as `signing.password`                                                            |

## License

Licensed under the Apache License, Version 2.0.
