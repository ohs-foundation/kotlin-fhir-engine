import dev.ohs.fhir.engine.codegen.GenerateSearchParamsTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.serialization)
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

  jvm("desktop")

  // Note: iosX64 (Intel iOS simulator) is omitted because Room 3 (androidx.room3) does not publish
  // iosX64 artifacts; including it breaks dependency resolution.
  iosArm64()
  iosSimulatorArm64()

  js { browser() }

  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

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
        implementation(libs.kotlin.fhir)
        implementation(libs.fhir.path)
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
    // platform source sets (web uses the Web Worker driver from :sqlite-wasm-worker instead).
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
    webMain.dependencies { implementation(project(":sqlite-wasm-worker")) }
    val desktopTest by getting {
      // `SearchParameterRepositoryGeneratedTest` reads the same FHIR R4 search-parameters bundle
      // the codegen consumes at build time, so the test classpath needs access to it.
      resources.srcDir(rootProject.file("buildSrc/src/main/resources"))
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
