import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  jvmToolchain(21)

  androidLibrary {
    namespace = "dev.ohs.fhir.codelab.shared"
    compileSdk = 36
    minSdk = 26
    androidResources { enable = true }
  }

  jvm("desktop")

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "CodelabKit"
      isStatic = true
    }
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      api("dev.ohs.fhir:fhir-engine:2.0.0-alpha05")
      api("dev.ohs.fhir:fhir-model-r4:1.0.0-rc03")
      implementation("dev.ohs.fhir:fhir-data-capture:2.0.0-alpha05")
      implementation("dev.ohs.fhir:fhir-workflow:2.0.0-alpha01")
      implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
      implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.0.1")

      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
      implementation(compose.components.resources)
    }
    androidMain.dependencies { implementation(libs.androidx.work.runtime) }
    val desktopMain by getting {
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
      }
    }
    // The engine's web worker is a local npm module that Maven consumers must supply themselves.
    // See "Required workaround for consumers targeting js/wasmJs" in the root README.
    wasmJsMain.dependencies {
      implementation(
        npm(
          "sqlite-wasm-worker",
          layout.projectDirectory.dir("src/wasmJsMain/npm/sqlite-wasm-worker").asFile,
        ),
      )
    }
  }
}

compose.resources { packageOfResClass = "dev.ohs.fhir.codelab.resources" }

compose.desktop { application { mainClass = "dev.ohs.fhir.codelab.MainKt" } }
