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

  // Micro-benchmarks live in their own compilation associated with `main`, which is what grants
  // them access to the engine's `internal` declarations — the same mechanism test compilations
  // use. Its default source set is `desktopBenchmark`. See docs/benchmarking.md.
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
      dependencies {
        implementation(libs.kotlinx.benchmark.runtime)
        // Benchmark-only: used to ask whether a binary payload is even representable here.
        implementation(libs.kotlinx.serialization.protobuf)
      }
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

// -Pbenchmark.tmpdir moves benchmark databases, e.g. onto tmpfs in CI to take disk jitter out.
// JMH forks inherit the host JVM's arguments, so setting it on the exec task reaches them.
tasks
  .withType<JavaExec>()
  .matching { it.name.startsWith("desktopBenchmark") }
  .configureEach {
    providers.gradleProperty("benchmark.tmpdir").orNull?.let { jvmArgs("-Djava.io.tmpdir=$it") }
  }

/** Benchmarks whose error on a shared CI runner needs more samples than the rest of the tier. */
val NOISY_ON_CI =
  "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\.(MoreResources|Resource(Insert|Update|Delete|Read))Benchmark"

benchmark {
  targets { register("desktopBenchmark") }
  configurations {
    named("main") {
      warmups = 5
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = "s"
    }
    // The per-pull-request tier. CI runs it twice in one job, against the base branch and then the
    // head, and comments with the difference — so it has to fit in a few minutes a side. Every
    // class runs, but the scaling sweeps are pinned to their smallest size: the shape of the curve
    // is a question for the full tier, and a regression at 1,000 rows is a regression at 50,000.
    register("pr") {
      exclude(NOISY_ON_CI)
      // Journal and fsync settings mean nothing on the tmpfs CI uses, and their question is
      // settled.
      exclude("dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\.SqliteTuningBenchmark")
      param("rows", 1000)
      param("changeCount", 50)
      // Five warmups: at three, CI still showed JIT drift in the first measured iterations.
      // Ten iterations: at five, Student's t (8.47) left most intervals too wide to see a 5%
      // change.
      warmups = 5
      iterations = 10
      iterationTime = 500
      iterationTimeUnit = "ms"
    }
    // The pull-request tier's other half: benchmarks whose single invocation is so slow that a
    // half-second iteration holds only one or two samples. An indexed update takes about 300 ms on
    // a CI runner, so its per-iteration score is essentially one measurement of a disk write, and
    // the CRUD and MoreResources rows came back at 30-60% error on the first run. Longer iterations
    // average more invocations into each score, which is what narrows that spread.
    register("prNoisy") {
      include(NOISY_ON_CI)
      // Five warmups: at three, insertIndexed's first measured iterations were still settling.
      warmups = 5
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = "s"
    }
    // Just the index-shape sweeps. They carry their own @Param grid, so running them apart from
    // the pure-CPU benchmarks keeps an A/B to about a minute instead of the full suite.
    register("index") {
      include(
        "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\.(DateIndexShape|StringIndexCollation|SqliteTuning|PayloadRepresentation|Resource(Insert|Update|Delete|Read))Benchmark",
      )
      warmups = 3
      iterations = 5
      iterationTime = 500
      iterationTimeUnit = "ms"
    }
  }
}

// kotlinx-benchmark builds its JMH Runner with shouldFailOnError left at JMH's default of false,
// and exposes no setting to change it. A benchmark whose @Setup throws is printed as `<failure>`,
// dropped from the JSON report — which carries no error field at all — and the process still exits
// 0, so a run that lost three of twenty benchmarks looks exactly like a green one. Watch the
// runner's own output instead, and fail the task on the markers it prints. See
// docs/benchmarking.md.
tasks.withType<JavaExec>().configureEach {
  // The plugin sets `group` after this action runs, so filter on the name instead.
  if (name.endsWith("Benchmark")) {
    val transcript = ByteArrayOutputStream()
    standardOutput = TeeOutputStream(System.out, transcript)
    doLast {
      val lines = transcript.toString().lineSequence().map { it.trim() }.toList()
      // A failed benchmark emits several markers, so the largest count is the number lost, not
      // their sum. "Failure:" alone is the runner itself failing before any benchmark ran.
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
    }
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
