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

import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Downloads the pinned Synthea release jar.
 *
 * Synthea is not on Maven Central, so it cannot be a normal dependency. The jar lands in the Gradle
 * user home rather than the project so it survives `clean`; it is roughly 200 MB.
 */
abstract class SyntheaDownloadTask : DefaultTask() {

  @get:Input abstract val version: Property<String>

  /** Guards against a tag being moved or the download being truncated. */
  @get:Input abstract val sha256: Property<String>

  @get:OutputFile abstract val jar: org.gradle.api.file.RegularFileProperty

  @TaskAction
  fun download() {
    val target = jar.get().asFile
    if (target.isFile && hash(target) == sha256.get()) {
      logger.lifecycle("Synthea ${version.get()} already present at ${target.absolutePath}")
      return
    }
    target.parentFile.mkdirs()
    val url =
      "https://github.com/synthetichealth/synthea/releases/download/${version.get()}/" +
        "synthea-with-dependencies.jar"
    logger.lifecycle("Downloading Synthea ${version.get()} (about 200 MB)")
    URI(url).toURL().openStream().use { input -> target.outputStream().use(input::copyTo) }

    val actual = hash(target)
    if (actual != sha256.get()) {
      target.delete()
      throw GradleException(
        "Synthea checksum mismatch for ${version.get()}.\n  expected ${sha256.get()}\n  actual   $actual",
      )
    }
  }

  private fun hash(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
