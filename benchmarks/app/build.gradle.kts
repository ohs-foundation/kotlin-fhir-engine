import javax.inject.Inject
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

// Only Android has a Compose UI; desktop and web print to a console. Compose does not initialise
// on the single-workload macrobenchmark path.
android {
  namespace = "dev.ohs.fhir.engine.benchmark.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.ohs.fhir.engine.benchmark.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Macrobenchmark measures the release build, which must be signed to install.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)

  androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

  jvm("desktop")

  js {
    browser()
    binaries.executable()
    useEsModules()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    binaries.executable()
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
    commonMain.dependencies {
      implementation(project(":benchmarks:core"))
      implementation(libs.kotlinx.coroutines.core)
      // The Compose compiler plugin fails any compilation without the runtime on its class path.
      implementation(compose.runtime)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    androidMain.dependencies {
      implementation(libs.kotlinx.coroutines.android)
      // Macrobenchmark drives profile compilation through this; API 34+ needs 1.4.0+.
      implementation(libs.androidx.profileinstaller)
      implementation(libs.androidx.activity.compose)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
    }
    webMain.dependencies { implementation(libs.kotlinx.browser) }
  }
}

/** Copies benchmark reports off the device; the app writes them where `adb pull` needs no root. */
val pullBenchmarkReports by
  tasks.registering {
    group = "verification"
    description = "adb pull the benchmark reports written by the driver app."

    val outputDir = layout.buildDirectory.dir("reports/benchmarks/android")
    val adb =
      providers
        .environmentVariable("ANDROID_HOME")
        .orElse(
          providers.gradleProperty("sdk.dir"),
        )
        .map { "$it/platform-tools/adb" }
        .orElse("adb")
    val packageName = android.defaultConfig.applicationId

    outputs.dir(outputDir)
    doLast {
      val destination = outputDir.get().asFile
      destination.mkdirs()
      val result =
        providers
          .exec {
            commandLine(
              adb.get(),
              "pull",
              "/sdcard/Android/data/$packageName/files/.",
              destination.absolutePath,
            )
            isIgnoreExitValue = true
          }
          .standardOutput
          .asText
          .get()
      logger.lifecycle(result.trim())
      val pulled = destination.listFiles()?.filter { it.extension == "json" }.orEmpty()
      if (pulled.isEmpty()) {
        logger.warn(
          "No benchmark reports found on the device. Run the driver app with -e groups <group> " +
            "first; a single-workload run measures through a trace and writes no report.",
        )
      } else {
        logger.lifecycle("Pulled ${pulled.size} report(s) to ${destination.absolutePath}")
      }
    }
  }

// ---------------------------------------------------------------------------------------------
// Synthea assets
//
// ./gradlew :benchmarks:app:installRelease -Pbenchmark.dataset=synthea
//
// The corpus ships inside the APK: an app cannot read /data/local/tmp from API 30.
// ---------------------------------------------------------------------------------------------

// The types a workload queries. Keep in sync with NdjsonDataset.INCLUDED_TYPES.
val benchmarkAssetTypes =
  listOf(
    "AllergyIntolerance",
    "CarePlan",
    "Condition",
    "Encounter",
    "Immunization",
    "MedicationRequest",
    "Observation",
    "Organization",
    "Patient",
    "Practitioner",
    "Procedure",
  )

/**
 * Copies the packaged corpus into one variant's assets.
 *
 * Registered through the variant API so asset merge, lint and packaging depend on it; AGP 8.13
 * drops the task dependency of a `Sync` wired through `assets.srcDir`.
 */
abstract class StageBenchmarkAssets @Inject constructor(private val fs: FileSystemOperations) :
  DefaultTask() {

  /** A file collection so an absent corpus is empty rather than a failure. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val syntheaData: ConfigurableFileCollection

  @get:Input abstract val types: ListProperty<String>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun stage() {
    val corpus = syntheaData.files.firstOrNull()?.takeIf { it.isDirectory }
    fs.sync {
      into(outputDirectory)
      if (corpus != null) {
        from(corpus) {
          into("bulk_data")
          include("manifest.json")
          types.get().forEach { include("$it.ndjson") }
        }
      }
    }
  }
}

val useSynthea =
  providers.gradleProperty("benchmark.dataset").orNull.equals("synthea", ignoreCase = true)

androidComponents {
  onVariants { variant ->
    val stage =
      tasks.register<StageBenchmarkAssets>(
        "stage${variant.name.replaceFirstChar { it.uppercase() }}BenchmarkAssets",
      ) {
        group = "benchmark data"
        description = "Copy the packaged Synthea data into the driver app's assets."
        syntheaData.from(
          project(":benchmarks:core").layout.buildDirectory.dir("benchmark-data/synthea"),
        )
        types.set(benchmarkAssetTypes)
        // Only build the corpus when a run asks for it.
        if (useSynthea) dependsOn(":benchmarks:core:packageBenchmarkData")
      }
    variant.sources.assets?.addGeneratedSourceDirectory(
      stage,
      StageBenchmarkAssets::outputDirectory,
    )
  }
}
