plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
  alias(libs.plugins.cashapp.licensee) apply false

  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.hotreload) apply false
  alias(libs.plugins.compose.multiplatform) apply false

  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.spotless)
}

//spotless {
//  val ktLintVersion = libs.versions.kt.lint.get()
//  val ktLintOptions =
//    mapOf(
//      "indent_size" to "2",
//      "continuation_indent_size" to "2",
//      "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
//    )
//
//  ratchetFrom = "origin/main"
//  kotlin {
//    target("**/*.kt")
//    targetExclude("**/build/", "**/*_Generated.kt")
//    ktlint(ktLintVersion).editorConfigOverride(ktLintOptions)
//    ktfmt().googleStyle()
//    licenseHeaderFile(
//      "${project.rootProject.projectDir}/license-header.txt",
//      "^(package|//startfile)|import|class|object|sealed|open|interface|abstract",
//    )
//  }
//  kotlinGradle {
//    target("**/*.gradle.kts")
//    ktlint(ktLintVersion).editorConfigOverride(ktLintOptions)
//    ktfmt().googleStyle()
//  }
//}
