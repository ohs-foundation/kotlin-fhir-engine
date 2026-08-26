plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.kotlin.android)
}

// Self-instrumenting: this module drives :benchmarks:app in a separate process and reads its trace
// sections back, rather than running inside it.
android {
  namespace = "dev.ohs.fhir.engine.benchmark.macro"
  compileSdk = 36

  defaultConfig {
    // TraceSectionMetric needs API 29.
    minSdk = 29
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Forwarded so -Pbenchmark.dataset=synthea both stages the data into the app's assets and tells
    // the run to use it. Setting only one of the two silently measures the wrong dataset.
    providers.gradleProperty("benchmark.dataset").orNull?.let {
      testInstrumentationRunnerArguments["dataset"] = it
    }
    providers.gradleProperty("benchmark.profile").orNull?.let {
      testInstrumentationRunnerArguments["profile"] = it
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  kotlin {
    jvmToolchain(21)
    compilerOptions {
      // TraceSectionMetric is still marked experimental in androidx.benchmark 1.4.x.
      optIn.add("androidx.benchmark.macro.ExperimentalMetricApi")
    }
  }

  // Matches the app's release variant: macrobenchmark must measure a non-debuggable build. The
  // test APK still needs signing or it fails to install with INSTALL_PARSE_FAILED_NO_CERTIFICATES.
  buildTypes { create("release") { signingConfig = signingConfigs.getByName("debug") } }

  targetProjectPath = ":benchmarks:app"
  experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
  implementation(libs.androidx.benchmark.macro.junit4)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
  // For the workload catalogue only: the macro module names workloads, it does not define them.
  implementation(project(":benchmarks:core"))
}
