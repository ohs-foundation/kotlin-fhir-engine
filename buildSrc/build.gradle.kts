plugins { `kotlin-dsl` }

repositories {
  google()
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation(libs.kotlinpoet)
  implementation(libs.kotlin.fhir)
  implementation(libs.kotlinx.serialization.json)
}

// fhir-model is built with Kotlin 2.2; the kotlin-dsl plugin in Gradle 8.13 still uses 2.0.
// Allow reading the newer metadata.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") }
}
