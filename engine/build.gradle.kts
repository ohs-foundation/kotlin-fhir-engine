import dev.ohs.fhir.engine.codegen.GenerateSearchParamsTask
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import org.apache.tools.ant.util.TeeOutputStream
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.allopen)
  alias(libs.plugins.kotlinx.benchmark)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.room3)
}

val mavenGroupId: String by project
val mavenArtifactId: String by project
val mavenVersion: String by project

val generateSearchParamsTask =
  tasks.register("generateSearchParamsTask", GenerateSearchParamsTask::class) {
    srcOutputDir.set(layout.buildDirectory.dir("generated/sources/searchparams/commonMain/kotlin"))
  }

val sqlCipherDir = layout.buildDirectory.dir("sqlcipher")
val sqlCipherVersion = "4.19.0"
val sqlCipherSha256 = "39f02d2f04f0de2ba1facf215550bfc6e6e2c9971d5d8ebb0cdd604874781bd7"

val downloadSqlCipher by
  tasks.registering {
    val zip = sqlCipherDir.map { it.file("SQLCipher.xcframework.zip") }
    inputs.property("version", sqlCipherVersion)
    inputs.property("sha256", sqlCipherSha256)
    outputs.dir(sqlCipherDir)
    doLast {
      val file = zip.get().asFile
      if (!file.exists()) {
        file.parentFile.mkdirs()
        URI(
            "https://github.com/sqlcipher/SQLCipher.swift/releases/download/$sqlCipherVersion/SQLCipher.xcframework.zip",
          )
          .toURL()
          .openStream()
          .use { it.copyTo(file.outputStream()) }
      }
      val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
      val actual = digest.joinToString("") { "%02x".format(it) }
      check(actual == sqlCipherSha256) {
        file.delete()
        "SQLCipher download checksum mismatch, expected $sqlCipherSha256 but was $actual"
      }
      ProcessBuilder(
          "unzip",
          "-q",
          "-o",
          file.path,
          "SQLCipher.xcframework/ios-arm64_x86_64-simulator/*",
          "-d",
          file.parentFile.path,
        )
        .inheritIO()
        .start()
        .waitFor()
        .let { check(it == 0) { "unzip failed with $it" } }
    }
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

  // Benchmarks live in desktopBenchmark, associated with main so they can use internal
  // declarations.
  jvm("desktop") {
    val mainCompilation = compilations.getByName("main")
    compilations.create("benchmark") { associateWith(mainCompilation) }
  }

  // Note: iosX64 (Intel iOS simulator) is omitted because Room 3 (androidx.room3) does not publish
  // iosX64 artifacts; including it breaks dependency resolution.
  iosArm64()
  iosSimulatorArm64 {
    // On iOS the app links SQLite, not the engine. The default test binary links SQLCipher, so
    // the plain and the encrypted database tests both run on it.
    binaries.getTest(NativeBuildType.DEBUG).apply {
      val slice = sqlCipherDir.map { it.dir("SQLCipher.xcframework/ios-arm64_x86_64-simulator") }
      linkerOpts(
        "-F",
        slice.get().asFile.path,
        "-framework",
        "SQLCipher",
        "-rpath",
        slice.get().asFile.path,
      )
      linkTaskProvider.configure { dependsOn(downloadSqlCipher) }
    }
    // A second test binary links the system SQLite, which most apps use. Its task is
    // iosSimulatorArm64SystemSqliteTest.
    binaries.test("systemSqlite", listOf(NativeBuildType.DEBUG)) { linkerOpts("-lsqlite3") }
    testRuns.create("systemSqlite") {
      setExecutionSourceFrom(binaries.getTest("systemSqlite", NativeBuildType.DEBUG))
    }
  }

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
        implementation(libs.sqlcipher.android)
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
        // Calls the SQLite the app links, the system one or SQLCipher. The engine bundles none.
        implementation(libs.androidx.sqlite.framework)
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

/**
 * Shard 1 of a CI run; shard 2 runs everything else. Split by run time. Each shard needs a
 * [noisyOnCi] class, or its prNoisy run matches nothing and fails.
 */
val firstShard =
  "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\." +
    "(SearchExecution|SearchResultSize|Sort|LocalChangeRead|DatabaseOpen|ResourceRead)Benchmark\\."

benchmark {
  targets { register("desktopBenchmark") }
  configurations {
    named("main") {
      warmups = 5
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = "s"
    }
    // The pull-request tier, with each sweep at its smallest size. Three forks, because JIT
    // decisions vary between forks more than a single fork's interval shows.
    register("pr") {
      exclude(noisyOnCi)
      // One invocation takes a second or more.
      exclude(
        "dev\\.ohs\\.fhir\\.engine\\.microbenchmark\\.(SyncDownload|ConcurrentAccess)Benchmark",
      )
      param("rows", 1000)
      param("changeCount", 50)
      param("results", 100)
      // The index the engine ships; the alternatives run in `index`.
      param("shape", "current")
      param("collation", "binary")
      warmups = 3
      iterations = 5
      iterationTime = 500
      iterationTimeUnit = "ms"
      advanced("jvmForks", 3)
    }
    // The pull-request tier's slow benchmarks.
    register("prNoisy") {
      include(noisyOnCi)
      warmups = 5
      iterations = 5
      iterationTime = 1
      iterationTimeUnit = "s"
      advanced("jvmForks", 3)
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
  // -Pbenchmark.shard splits every configuration across CI jobs.
  when (val shard = providers.gradleProperty("benchmark.shard").orNull) {
    null -> {}
    "1" -> configurations.configureEach { exclude("^(?!$firstShard)") }
    "2" -> configurations.configureEach { exclude(firstShard) }
    else -> throw GradleException("Unknown benchmark.shard: $shard")
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

// Exports the Room schema for every target into one directory (the schema is identical across
// targets). Committed so migrations can be tested against previous versions.
room3 { schemaDirectory("$projectDir/schemas") }

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
      excludeTestsMatching("dev.ohs.fhir.engine.db.impl.ResourceDatabaseMigrationTest")
      excludeTestsMatching("dev.ohs.fhir.engine.db.impl.JournalModeTest")
      excludeTestsMatching("dev.ohs.fhir.engine.db.impl.DatabaseFileNameTest")
    }
  }

// OPFS may not support WAL, so JournalModeTest does not run on web.
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
