@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Provides a web SQLite driver for Room 3 on js/wasmJs: a Web Worker (running SQLite-WASM with
 * OPFS) plus the [androidx.sqlite.driver.web.WebWorkerSQLiteDriver] that talks to it. Modeled on
 * the official room-web-demo (github.com/danysantiago/room-web-demo).
 */
plugins { alias(libs.plugins.kotlin.multiplatform) }

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
