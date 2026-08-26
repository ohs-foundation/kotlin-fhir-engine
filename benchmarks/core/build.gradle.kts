import dev.ohs.fhir.engine.benchmark.PackageBenchmarkDataTask
import dev.ohs.fhir.engine.benchmark.SyntheaDownloadTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(21)

  // Mirrors :engine's target set: a benchmark that skips a target cannot answer whether that target
  // got slower.
  androidLibrary {
    namespace = "dev.ohs.fhir.engine.benchmark.core"
    compileSdk = 36
    minSdk = 26
    withHostTestBuilder {}
    withDeviceTestBuilder { sourceSetTreeName = "test" }
      .configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
  }

  jvm("desktop")

  // iosX64 omitted for the same reason as :engine — Room 3 publishes no iosX64 artifacts.
  iosArm64()
  iosSimulatorArm64()

  js {
    browser()
    useEsModules()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    useEsModules()
  }

  targets.configureEach {
    compilations.configureEach {
      compilerOptions.configure {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll("kotlin.time.ExperimentalTime", "kotlin.uuid.ExperimentalUuidApi")
      }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        api(project(":engine"))
        implementation(libs.fhir.model.r4)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.datetime)
        implementation(libs.kotlinx.serialization.json)
      }
    }
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
      }
    }
    androidMain.dependencies {
      // Emits the trace sections macrobenchmark's TraceSectionMetric reads.
      implementation(libs.androidx.tracing)
    }
    webMain.dependencies {
      // For navigator.userAgent in the report's platform descriptor.
      implementation(libs.kotlinx.browser)
    }
  }
}

// Forward -P controls to the desktop harness. Registered as inputs so changing a profile re-runs
// rather than serving the previous profile's result.
tasks.named<Test>("desktopTest") {
  listOf(
      "benchmark.profile",
      "benchmark.dataset",
      "benchmark.seed",
      "benchmark.warmup",
      "benchmark.iterations",
      "benchmark.groups",
      "benchmark.report.dir",
      "benchmark.storage.dir",
      "benchmark.data.dir",
      "benchmark.server",
    )
    .forEach { key ->
      val value = project.findProperty(key)?.toString()
      if (value != null) {
        systemProperty(key, value)
        inputs.property(key, value)
      }
    }
  // Real datasets are held in memory while they are inserted.
  maxHeapSize = "4g"
  // A benchmark run is never up to date; the point is to measure again.
  outputs.upToDateWhen { false }
  testLogging { showStandardStreams = true }
}

// ---------------------------------------------------------------------------------------------
// Synthea dataset
//
// ./gradlew :benchmarks:core:packageBenchmarkData -Pbenchmark.population=100
// ./gradlew :benchmarks:core:desktopTest -Pbenchmark.dataset=synthea
// ---------------------------------------------------------------------------------------------

val syntheaRelease = providers.gradleProperty("synthea.version").getOrElse("v4.0.0")
val syntheaSha256 = providers.gradleProperty("synthea.sha256").get()
val benchmarkPopulation = providers.gradleProperty("benchmark.population").getOrElse("100").toInt()
val benchmarkSeed = providers.gradleProperty("benchmark.seed").getOrElse("20260819")

// Outside the project tree so a 200 MB jar survives `clean` and is shared between checkouts.
val syntheaJar =
  File(gradle.gradleUserHomeDir, "caches/synthea/$syntheaRelease/synthea-with-dependencies.jar")

val downloadSynthea by
  tasks.registering(SyntheaDownloadTask::class) {
    group = "benchmark data"
    description = "Download the pinned Synthea release jar."
    version.set(syntheaRelease)
    sha256.set(syntheaSha256)
    jar.set(syntheaJar)
  }

val syntheaRawOutput = layout.buildDirectory.dir("benchmark-data/raw")

val generateSyntheaData by
  tasks.registering(JavaExec::class) {
    group = "benchmark data"
    description = "Generate Synthea patient records."
    dependsOn(downloadSynthea)
    classpath = files(syntheaJar)
    mainClass.set("App")

    inputs.property("population", benchmarkPopulation)
    inputs.property("seed", benchmarkSeed)
    inputs.property("syntheaVersion", syntheaRelease)
    outputs.dir(syntheaRawOutput)

    argumentProviders.add(
      CommandLineArgumentProvider {
        listOf(
          "-p",
          benchmarkPopulation.toString(),
          "-s",
          benchmarkSeed,
          "-cs",
          benchmarkSeed,
          // One ndjson per resource type, which is the layout android-fhir's benchmarks use.
          "--exporter.fhir.bulk_data=true",
          "--exporter.baseDirectory=${syntheaRawOutput.get().asFile.absolutePath}",
        )
      },
    )

    doFirst { syntheaRawOutput.get().asFile.deleteRecursively() }
  }

val benchmarkDataDir = layout.buildDirectory.dir("benchmark-data/synthea")

val packageBenchmarkData by
  tasks.registering(PackageBenchmarkDataTask::class) {
    group = "benchmark data"
    description = "Normalise Synthea output into one file per resource type, plus a manifest."
    dependsOn(generateSyntheaData)
    syntheaOutput.set(syntheaRawOutput)
    destination.set(benchmarkDataDir)
    syntheaVersion.set(syntheaRelease)
    population.set(benchmarkPopulation)
    seed.set(benchmarkSeed)
  }

tasks.named<Test>("desktopTest") {
  // Only build the dataset when a run actually asks for it: generating it takes minutes.
  if (
    providers.gradleProperty("benchmark.dataset").orNull.equals("synthea", ignoreCase = true) &&
      providers.gradleProperty("benchmark.data.dir").orNull == null
  ) {
    dependsOn(packageBenchmarkData)
    systemProperty("benchmark.data.dir", benchmarkDataDir.get().asFile.absolutePath)
  }
}

// ---------------------------------------------------------------------------------------------
// Browser harness
//
// export CHROME_BIN=/path/to/Chromium
// ./gradlew :benchmarks:core:jsBrowserTest -Pbenchmark.profile=smoke
// ---------------------------------------------------------------------------------------------

// A browser reads neither -P properties nor the filesystem, so the run config is written where the
// Karma middleware can serve it; see karma.config.d/benchmark-server.js.
val webBenchmarkProperties =
  listOf(
      "benchmark.profile",
      "benchmark.dataset",
      "benchmark.seed",
      "benchmark.warmup",
      "benchmark.iterations",
      "benchmark.groups",
    )
    .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { key to it } }
    .toMap()

val webConfigFile = layout.buildDirectory.file("benchmark-web-config.json")

val writeWebBenchmarkConfig by
  tasks.registering {
    group = "benchmark"
    description = "Put the -Pbenchmark.* values where the browser harness can fetch them."
    outputs.upToDateWhen { false }
    doLast {
      val file = webConfigFile.get().asFile
      file.parentFile.mkdirs()
      val groups =
        (webBenchmarkProperties["benchmark.groups"] ?: "crud,search,sync").split(",").joinToString(
          ", ",
        ) {
          "\"${it.trim()}\""
        }
      val dataset =
        if (webBenchmarkProperties["benchmark.dataset"].equals("synthea", ignoreCase = true)) {
          "synthea"
        } else {
          "synthetic"
        }
      // Defaults are the browser's own rather than desktop's: an unflagged browser run stays small
      // because a browser over OPFS is the slowest target by a wide margin.
      file.writeText(
        """
        {
          "profile": "${(webBenchmarkProperties["benchmark.profile"] ?: "smoke").lowercase()}",
          "datasetKind": "$dataset",
          "seed": ${webBenchmarkProperties["benchmark.seed"]?.toIntOrNull() ?: 20260819},
          "warmupIterations": ${webBenchmarkProperties["benchmark.warmup"]?.toIntOrNull() ?: 1},
          "measuredIterations": ${webBenchmarkProperties["benchmark.iterations"]?.toIntOrNull() ?: 3},
          "groups": [$groups]
        }
        """
          .trimIndent(),
      )
    }
  }

listOf("jsBrowserTest", "wasmJsBrowserTest").forEach { name ->
  tasks.named(name) {
    dependsOn(writeWebBenchmarkConfig)
    // The middleware serves this fixed directory, so -Pbenchmark.data.dir does not apply on web.
    if (providers.gradleProperty("benchmark.dataset").orNull.equals("synthea", true)) {
      dependsOn(packageBenchmarkData)
    }
    outputs.upToDateWhen { false }
  }
}

// ---------------------------------------------------------------------------------------------
// iOS simulator harness
//
// ./gradlew :benchmarks:core:iosSimulatorArm64Test -Pbenchmark.dataset=synthea
// ---------------------------------------------------------------------------------------------

// A simulator shares the host filesystem, so the report and the dataset are ordinary host paths;
// the harness reads them from the environment. A real device would need them bundled instead.
tasks
  .withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>()
  .configureEach {
    // simctl only forwards variables prefixed SIMCTL_CHILD_ into the simulated process; without the
    // prefix the harness never sees them and silently writes its report inside the simulator.
    fun forSimulator(name: String, value: String) {
      environment(name, value)
      environment("SIMCTL_CHILD_$name", value)
    }

    forSimulator(
      "BENCHMARK_REPORT_DIR",
      layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath,
    )
    listOf(
        "benchmark.profile",
        "benchmark.dataset",
        "benchmark.seed",
        "benchmark.warmup",
        "benchmark.iterations",
        "benchmark.groups",
        "benchmark.server",
      )
      .forEach { key ->
        providers.gradleProperty(key).orNull?.let {
          forSimulator(key.replace(".", "_").uppercase(), it)
        }
      }
    if (providers.gradleProperty("benchmark.dataset").orNull.equals("synthea", true)) {
      dependsOn(packageBenchmarkData)
      forSimulator("BENCHMARK_DATA_DIR", benchmarkDataDir.get().asFile.absolutePath)
    }
    outputs.upToDateWhen { false }
  }
