plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.ohs.fhir.codelab"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.ohs.fhir.codelab"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { buildConfig = true }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)
  androidTarget()

  sourceSets {
    androidMain.dependencies {
      implementation(project(":shared"))
      implementation(libs.androidx.activity.compose)
      implementation(libs.androidx.work.runtime)
      implementation(compose.material3)
    }
  }
}
