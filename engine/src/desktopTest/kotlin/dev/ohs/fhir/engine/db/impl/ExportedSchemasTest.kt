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
package dev.ohs.fhir.engine.db.impl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fails when [ExportedSchemas] no longer matches the schema files. */
class ExportedSchemasTest {
  @Test
  fun constantsMatchTheExportedSchemaFiles() {
    val directory = File("schemas/dev.ohs.fhir.engine.db.impl.ResourceDatabase")
    for (version in 1..ResourceDatabase.VERSION) {
      val database =
        Json.parseToJsonElement(File(directory, "$version.json").readText())
          .jsonObject["database"]!!
      val expected =
        database.jsonObject["entities"]!!.jsonArray.flatMap { entity ->
          val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
          val create = entity.jsonObject["createSql"]!!.jsonPrimitive.content
          val indices =
            entity.jsonObject["indices"]?.jsonArray?.map {
              it.jsonObject["createSql"]!!.jsonPrimitive.content
            }
              ?: emptyList()
          (listOf(create) + indices).map { it.replace("\${TABLE_NAME}", table) }
        }
      assertEquals(expected, ExportedSchemas.ddl(version), "schema $version")
    }
  }
}
