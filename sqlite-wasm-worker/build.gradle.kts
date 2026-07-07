@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Provides a web SQLite driver for Room 3 on wasmJs: a Web Worker (running SQLite-WASM with OPFS)
 * plus the [androidx.sqlite.driver.web.WebWorkerSQLiteDriver] that talks to it. Modeled on the
 * official room-web-demo (github.com/danysantiago/room-web-demo).
 */
plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvmToolchain(21)

  wasmJs {
    browser()
    useEsModules()
  }

  sourceSets {
    commonMain.dependencies {
      // Exposes WebWorkerSQLiteDriver to consumers (engine wasm source set).
      api(libs.androidx.sqlite.web)
      // Local npm module: worker.js + @sqlite.org/sqlite-wasm (see ./worker).
      implementation(npm("sqlite-wasm-worker", layout.projectDirectory.dir("worker").asFile))
    }
    val wasmJsMain by getting {
      dependencies { implementation(libs.kotlinx.browser) }
    }
  }
}
