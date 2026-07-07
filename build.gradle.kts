plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.maven.publish) apply false
}

allprojects { configureSpotless() }

// The Node.js version Kotlin downloads for the JS/wasmJs build crashes on older macOS
// (dyld: __libcpp_verbose_abort). When the `nodeHome` Gradle property points at a working
// system Node install (dir containing bin/node), use that instead of downloading.
// Set it per-machine, e.g. in ~/.gradle/gradle.properties:
//   nodeHome=/path/to/node/v22.20.0
val nodeHome: String? = (findProperty("nodeHome") as String?)?.takeIf { it.isNotBlank() }

if (nodeHome != null) {
  val nodeDir = file(nodeHome)
  plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().apply {
      download.set(false)
      installationDirectory.fileValue(nodeDir)
    }
  }
  plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().download.set(false)
  }
  plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().apply {
      download.set(false)
      installationDirectory.fileValue(nodeDir)
    }
  }
  plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec>().download.set(false)
  }
}
