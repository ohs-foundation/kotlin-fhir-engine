import dev.ohs.fhir.engine.codegen.GenerateSearchParamsTask
import java.io.ByteArrayOutputStream
import org.apache.tools.ant.util.TeeOutputStream
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.allopen)
  alias(libs.plugins.kotlinx.benchmark)
  alias(libs.plugins.maven.publish)
}

val mavenGroupId: String by project
val mavenArtifactId: String by project
val mavenVersion: String by project

val generateSearchParamsTask =
  tasks.register("generateSearchParamsTask", GenerateSearchParamsTask::class) {
    srcOutputDir.set(layout.buildDirectory.dir("generated/sources/searchparams/commonMain/kotlin"))
  }

kotlin {
  jvmToolchain(21)

  androidLibrary {
    namespace = "dev.ohs.fhir.engine"
    compileSdk = 36
    minSdk = 26
    withHostTestBuilder {}
    withDeviceTestBuilder { sourceSetTreeName = "test" }
      .configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
  }

  // Associated with `main` so benchmarks can use `internal` declarations. Source set:
  // `desktopBenchmark`.
  jvm("desktop") {
    val mainCompilation = compilations.getByName("main")
    compilations.create("benchmark") { associateWith(mainCompilation) }
  }

  // Note: iosX64 (Intel iOS simulator) is omitted because Room 3 (androidx.room3) does not publish
  // iosX64 artifacts; including it breaks dependency resolution.
  iosArm64()
  iosSimulatorArm64()

  // useEsModules() is required so the SQLite-WASM Web Worker (loaded via `new Worker(new
  // URL(..., import.meta.url), { type: "module" })`) can be resolved as an ES module.
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
      kotlin.srcDir(generateSearchParamsTask.map { it.srcOutputDir })
      dependencies {
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.datetime)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.fhir.model.r4)
        implementation(libs.fhir.path.r4)
        implementation(libs.kermit)
        implementation(libs.androidx.room3.runtime)
        implementation(libs.androidx.sqlite.async)
        api(libs.androidx.datastore.preferences.core)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.client.logging)
        implementation(libs.ktor.client.encoding)
        implementation(libs.ktor.client.auth)
        implementation(libs.ktor.serialization.kotlinx.json)
      }
    }
    commonTest {
      dependencies {
        implementation(libs.kotest.assertions.core)
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.ktor.client.mock.engine)
      }
    }
    // The bundled native SQLite driver has no web artifact, so it lives only in the non-web
    // platform source sets (web uses the Web Worker driver from webMain instead).
    val androidMain by getting {
      dependencies {
        implementation(libs.androidx.sqlite.bundled)
        implementation(libs.androidx.work.runtime)
        implementation(libs.androidx.lifecycle.livedata)
        implementation(libs.ktor.client.okhttp)
      }
    }
    val desktopMain by getting {
      dependencies {
        implementation(libs.androidx.sqlite.bundled)
        implementation(libs.ktor.client.java)
      }
    }
    iosMain {
      dependencies {
        implementation(libs.androidx.sqlite.bundled)
        implementation(libs.ktor.client.darwin)
      }
    }
    webMain.dependencies {
      // Exposes WebWorkerSQLiteDriver to consumers of DatabaseBuilder.kt.
      api(libs.androidx.sqlite.web)
      implementation(libs.kotlinx.browser)
      // Local npm module: worker.js and @sqlite.org/sqlite-wasm (see src/webMain/npm).
      implementation(
        npm(
          "sqlite-wasm-worker",
          layout.projectDirectory.dir("src/webMain/npm/sqlite-wasm-worker").asFile,
        ),
      )
    }
    val desktopBenchmark by getting {
      dependencies { implementation(libs.kotlinx.benchmark.runtime) }
    }
    val desktopTest by getting {
      // `SearchParameterRepositoryGeneratedTest` reads the same FHIR R4 search-parameters bundle
      // the codegen consumes at build time, so the test classpath needs access to it.
      resources.srcDir(rootProject.file("buildSrc/src/main/resources"))
    }
    getByName("androidDeviceTest") {
      dependencies {
        implementation(libs.androidx.test.core)
        implementation(libs.androidx.test.runner)
        implementation(libs.kotlin.test.junit)
      }
    }
    getByName("androidHostTest") {
      dependencies {
        implementation(libs.junit)
        implementation(libs.robolectric)
        implementation(libs.androidx.test.core)
        implementation(libs.androidx.work.testing)
        implementation(libs.kotlin.test.junit)
        implementation(libs.kotlinx.coroutines.test)
      }
    }
  }
}

// JMH subclasses the @State class to generate its harness, and Kotlin classes are final by default.
allOpen { annotation("org.openjdk.jmh.annotations.State") }

// Runner tasks are named `desktopBenchmark<Configuration>Benchmark`. Matched by name because the
// plugin sets `group` after this runs.
val benchmarkRuns = tasks.withType<JavaExec>().matching { it.name.endsWith("Benchmark") }

benchmarkRuns.configureEach {
  // -Pbenchmark.tmpdir moves benchmark databases. JMH forks inherit these JVM arguments.
  providers.gradleProperty("benchmark.tmpdir").orNull?.let { jvmArgs("-Djava.io.tmpdir=$it") }

  // kotlinx-benchmark exits 0 and drops a benchmark from the report when it fails, with no setting
  // to change this. Fail the task on the failure markers in the runner's output instead.
  val transcript = ByteArrayOutputStream()
  // Both streams, in case a marker goes to stderr.
  standardOutput = TeeOutputStream(System.out, transcript)
  errorOutput = TeeOutputStream(System.err, transcript)
  doLast {
    val lines = transcript.toString().lineSequence().map { it.trim() }.toList()
    // One failure prints several markers, so take the largest count, not the sum. "Failure:" alone
    // means the runner failed before any benchmark ran.
    val count =
      maxOf(
        lines.count { it == "<failure>" },
        lines.count { it.startsWith("EXCEPTION:") },
        lines.count { it.startsWith("Failure:") },
      )
    if (count > 0) {
      throw GradleException(
        "$count benchmark(s) failed to run. kotlinx-benchmark omits them from the report and " +
          "exits 0, so this check is the only thing failing the build. See the output above.",
      )
    }
  }
}

/** Benchmarks whose single invocation is slow enough to need longer iterations. */
val noisyOnCi =
  "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\." +
    "(BulkImport|DatabaseOpen|Engine(Create|Update|Delete)|ResourceRead)Benchmark"

benchmark {
  targets { register("desktopBenchmark") }
  configurations {
    named("main") {
      warmups = 5
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = "s"
    }
    // The pull-request tier, with each sweep at its smallest size.
    register("pr") {
      exclude(noisyOnCi)
      // One invocation takes a second or more.
      exclude(
        "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\.(SyncDownload|ConcurrentAccess)Benchmark",
      )
      param("rows", 1000)
      param("changeCount", 50)
      param("results", 100)
      warmups = 5
      iterations = 10
      iterationTime = 500
      iterationTimeUnit = "ms"
    }
    // The pull-request tier's slow benchmarks.
    register("prNoisy") {
      include(noisyOnCi)
      warmups = 5
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = "s"
    }
    // The benchmarks with a @Param grid or a seeded corpus, at full size.
    register("index") {
      include(
        "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\." +
          "(DateIndexShape|StringIndexCollation|QuantityIndexShape|LookupIndexCovering|" +
          "TokenIndexShape|ConcurrentAccess|Sort|" +
          "SearchExecution|SearchResultSize|LocalChangeRead|BulkImport|DatabaseOpen|SyncDownload|" +
          "Engine(Create|Update|Delete)|ResourceRead)Benchmark",
      )
      warmups = 3
      iterations = 5
      iterationTime = 500
      iterationTimeUnit = "ms"
    }
  }
}

dependencies {
  listOf(
      "kspAndroid",
      "kspDesktop",
      "kspIosArm64",
      "kspIosSimulatorArm64",
      "kspJs",
      "kspWasmJs",
    )
    .forEach { add(it, libs.androidx.room3.compiler) }
}

tasks
  .withType<Test>()
  .matching { it.name == "testAndroidHostTest" }
  .configureEach {
    filter {
      // These tests need an Android Context, which host tests cannot provide. They run on every
      // other target and on Android in connectedAndroidDeviceTest.
      excludeTestsMatching("dev.ohs.fhir.engine.FhirEngineProviderTest")
      excludeTestsMatching("dev.ohs.fhir.engine.impl.FhirEngineImplTest")
      excludeTestsMatching("dev.ohs.fhir.engine.search.query.XFhirQueryTranslatorTest")
      excludeTestsMatching("dev.ohs.fhir.engine.db.impl.JournalModeTest")
    }
  }

// JournalModeTest asserts WAL. On web, OPFS may not support WAL, and it is not yet measured.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
  filter.excludeTestsMatching("dev.ohs.fhir.engine.db.impl.JournalModeTest")
}

mavenPublishing {
  publishToMavenCentral()
  signAllPublications()
  coordinates(mavenGroupId, mavenArtifactId, mavenVersion)

  pom {
    name = "Kotlin FHIR Engine"
    description =
      "A Kotlin Multiplatform library for on-device FHIR R4 persistence, search, and " +
        "synchronization with remote FHIR servers"
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
