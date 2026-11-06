plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  jvmToolchain(21)

  // Add more targets (jvm("desktop"), iosArm64(), wasmJs { browser() }) here as they are supported.
  androidLibrary {
    namespace = "dev.ohs.fhir.codelab.shared"
    compileSdk = 36
    minSdk = 26
  }

  sourceSets {
    commonMain.dependencies {
      api("dev.ohs.fhir:fhir-engine:2.0.0-alpha05")
      api(libs.fhir.model.r4)
      implementation(libs.kotlinx.coroutines.core)
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
    }
    androidMain.dependencies { implementation(libs.androidx.work.runtime) }
  }
}
