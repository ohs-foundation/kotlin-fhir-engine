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

package codegen

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.FhirR4Json
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateSearchParamsTask : DefaultTask() {
  @OutputDirectory val srcOutputDir = project.objects.directoryProperty()

  @TaskAction
  fun generateCode() {
    val json =
      GenerateSearchParamsTask::class.java.getResourceAsStream("/search-parameters.json").use {
        checkNotNull(it) { "Failed to get search-parameters.json" }
        it.bufferedReader().readText()
      }
    val bundle = FhirR4Json().decodeFromString(json) as Bundle
    val srcOut = srcOutputDir.asFile.get()
    srcOut.deleteRecursively()
    srcOut.mkdirs()
    SearchParameterRepositoryGenerator.generate(bundle = bundle, outputPath = srcOut)
  }
}
