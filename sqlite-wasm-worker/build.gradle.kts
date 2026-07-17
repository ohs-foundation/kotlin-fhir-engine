@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Provides a web SQLite driver for Room 3 on js/wasmJs: a Web Worker (running SQLite-WASM with
 * OPFS) plus the [androidx.sqlite.driver.web.WebWorkerSQLiteDriver] that talks to it. Modeled on
 * the official room-web-demo (github.com/danysantiago/room-web-demo).
 */
val mavenGroupId: String by project
val sqliteWasmWorkerArtifactId: String by project
val sqliteWasmWorkerVersion: String by project

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.maven.publish)
}

kotlin {
  jvmToolchain(21)

  js {
    browser()
    useEsModules()
  }

  wasmJs {
    browser()
    useEsModules()
  }

  targets.configureEach {
    compilations.configureEach {
      compilerOptions.configure { freeCompilerArgs.add("-Xexpect-actual-classes") }
    }
  }

  sourceSets {
    commonMain.dependencies {
      // Exposes WebWorkerSQLiteDriver to consumers (engine web source set).
      api(libs.androidx.sqlite.web)
      // Local npm module: worker.js and @sqlite.org/sqlite-wasm (see ./worker).
      implementation(npm("sqlite-wasm-worker", layout.projectDirectory.dir("worker").asFile))
    }
    webMain.dependencies { implementation(libs.kotlinx.browser) }
  }
}

mavenPublishing {
  publishToMavenCentral()
  signAllPublications()
  coordinates(mavenGroupId, sqliteWasmWorkerArtifactId, sqliteWasmWorkerVersion)

  pom {
    name = "SQLite Wasm Worker"
    description =
      "A Web Worker-backed SQLite driver for Room 3 on Kotlin/Wasm, used by the Kotlin FHIR " +
        "Engine's web target"
    inceptionYear = "2026"
    url = "https://github.com/ohs-foundation/kotlin-fhir-engine"
    licenses {
      license {
        name = "The Apache License, Version 2.0"
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
      }
    }
    developers {
      developer {
        id = "ohs-foundation"
        name = "Open Health Stack Foundation"
        url = "https://ohs.dev/"
      }
    }
    scm {
      url = "https://github.com/ohs-foundation/kotlin-fhir-engine/"
      connection = "scm:git:git://github.com/ohs-foundation/kotlin-fhir-engine.git"
      developerConnection = "scm:git:ssh://git@github.com/ohs-foundation/kotlin-fhir-engine.git"
    }
  }
}
