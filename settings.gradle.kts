pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "kotlin-fhir-engine"

include(":engine")

include(":engine-app")

include(":benchmarks:core")

include(":benchmarks:app")

include(":benchmarks:macro")
