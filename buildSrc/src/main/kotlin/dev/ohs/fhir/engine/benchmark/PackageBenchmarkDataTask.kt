/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine.benchmark

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Normalises Synthea's output into one file per resource type, plus a manifest.
 *
 * Synthea writes some types with a run timestamp in the filename, e.g.
 * `Organization.1787101630467.ndjson`, so the raw output is not reproducible from one run to the
 * next. Everything for a type is merged into `<Type>.ndjson`.
 */
abstract class PackageBenchmarkDataTask : DefaultTask() {

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val syntheaOutput: DirectoryProperty

  @get:OutputDirectory abstract val destination: DirectoryProperty

  @get:Input abstract val syntheaVersion: Property<String>

  @get:Input abstract val population: Property<Int>

  @get:Input abstract val seed: Property<String>

  @TaskAction
  fun packageData() {
    val fhir = syntheaOutput.get().asFile.resolve("fhir")
    if (!fhir.isDirectory) {
      throw GradleException("No fhir/ directory under ${syntheaOutput.get().asFile}.")
    }
    val out = destination.get().asFile
    out.deleteRecursively()
    out.mkdirs()

    val counts = sortedMapOf<String, Int>()
    fhir
      .listFiles { file -> file.isFile && file.name.endsWith(".ndjson") }
      .orEmpty()
      .sortedBy { it.name }
      .forEach { file ->
        val type = file.name.substringBefore(".")
        val lines = file.readLines().filter { it.isNotBlank() }
        out.resolve("$type.ndjson").appendText(lines.joinToString("\n", postfix = "\n"))
        counts[type] = (counts[type] ?: 0) + lines.size
      }

    if (counts.isEmpty()) throw GradleException("Synthea produced no ndjson files.")

    val fingerprint = counts.entries.joinToString(",") { "${it.key}=${it.value}" }.hashCode()
    out
      .resolve("manifest.json")
      .writeText(
        buildString {
          appendLine("{")
          appendLine("""  "syntheaVersion": "${syntheaVersion.get()}",""")
          appendLine("""  "population": ${population.get()},""")
          appendLine("""  "seed": "${seed.get()}",""")
          appendLine("""  "fingerprint": "${fingerprint.toUInt().toString(16)}",""")
          appendLine("""  "resourceCounts": {""")
          appendLine(counts.entries.joinToString(",\n") { """    "${it.key}": ${it.value}""" })
          appendLine("  }")
          append("}")
        },
      )
    logger.lifecycle(
      "Packaged ${counts.values.sum()} resources across ${counts.size} types into ${out.absolutePath}",
    )
  }
}
