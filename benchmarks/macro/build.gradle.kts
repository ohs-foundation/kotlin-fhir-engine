plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.kotlin.android)
}

// Self-instrumenting: drives :benchmarks:app in a separate process and reads its trace sections.
android {
  namespace = "dev.ohs.fhir.engine.benchmark.macro"
  compileSdk = 36

  defaultConfig {
    // TraceSectionMetric needs API 29.
    minSdk = 29
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Forwarded so one -Pbenchmark.dataset both stages the app's assets and selects the dataset.
    providers.gradleProperty("benchmark.dataset").orNull?.let {
      testInstrumentationRunnerArguments["dataset"] = it
    }
    providers.gradleProperty("benchmark.profile").orNull?.let {
      testInstrumentationRunnerArguments["profile"] = it
    }
    // Used only by the server group.
    providers.gradleProperty("benchmark.server").orNull?.let {
      testInstrumentationRunnerArguments["server"] = it
    }
    providers.gradleProperty("benchmark.mix").orNull?.let {
      testInstrumentationRunnerArguments["mix"] = it
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  kotlin {
    jvmToolchain(21)
    compilerOptions {
      // TraceSectionMetric is experimental in androidx.benchmark 1.4.x.
      optIn.add("androidx.benchmark.macro.ExperimentalMetricApi")
    }
  }

  // Matches the app's non-debuggable release variant. The test APK must be signed to install.
  buildTypes { create("release") { signingConfig = signingConfigs.getByName("debug") } }

  targetProjectPath = ":benchmarks:app"
  experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
  implementation(libs.androidx.benchmark.macro.junit4)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
  // For the workload catalogue only.
  implementation(project(":benchmarks:core"))
}
