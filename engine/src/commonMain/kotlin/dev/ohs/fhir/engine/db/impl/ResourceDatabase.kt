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

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import dev.ohs.fhir.engine.db.impl.dao.LocalChangeDao
import dev.ohs.fhir.engine.db.impl.dao.ResourceDao
import dev.ohs.fhir.engine.db.impl.entities.DateIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.DateTimeIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.LocalChangeEntity
import dev.ohs.fhir.engine.db.impl.entities.LocalChangeResourceReferenceEntity
import dev.ohs.fhir.engine.db.impl.entities.NumberIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.PositionIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.QuantityIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.ReferenceIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.ResourceEntity
import dev.ohs.fhir.engine.db.impl.entities.StringIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.TokenIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.UriIndexEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Database(
  entities =
    [
      ResourceEntity::class,
      StringIndexEntity::class,
      ReferenceIndexEntity::class,
      TokenIndexEntity::class,
      QuantityIndexEntity::class,
      UriIndexEntity::class,
      DateIndexEntity::class,
      DateTimeIndexEntity::class,
      NumberIndexEntity::class,
      PositionIndexEntity::class,
      LocalChangeEntity::class,
      LocalChangeResourceReferenceEntity::class,
    ],
  version = ResourceDatabase.VERSION,
  exportSchema = true,
)
@ColumnTypeConverters(DbTypeConverters::class)
@ConstructedBy(ResourceDatabaseConstructor::class)
internal abstract class ResourceDatabase : RoomDatabase() {
  abstract fun resourceDao(): ResourceDao

  abstract fun localChangeDao(): LocalChangeDao

  companion object {
    const val VERSION = 11

    val MIGRATION_1_2 =
      Migration(1, 2) { c -> c.executeSQL("DROP TABLE IF EXISTS SyncedResourceEntity") }

    val MIGRATION_2_3 =
      Migration(2, 3) { c ->
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_DateTimeIndexEntity_index_from` ON `DateTimeIndexEntity` (`index_from`)",
        )
      }

    val MIGRATION_3_4 =
      Migration(3, 4) { c ->
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_DateTimeIndexEntity_resourceType_index_name_resourceUuid_index_from_index_to` ON `DateTimeIndexEntity` (`resourceType`, `index_name`, `resourceUuid`, `index_from`, `index_to`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_DateIndexEntity_resourceType_index_name_resourceUuid_index_from_index_to` ON `DateIndexEntity` (`resourceType`, `index_name`, `resourceUuid`, `index_from`, `index_to`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_TokenIndexEntity_resourceType_index_name_index_system_index_value_resourceUuid` ON `TokenIndexEntity` (`resourceType`, `index_name`, `index_system`, `index_value`, `resourceUuid`)",
        )
        c.executeSQL("DROP INDEX IF EXISTS `index_DateTimeIndexEntity_index_from`")
        c.executeSQL(
          "DROP INDEX IF EXISTS `index_DateTimeIndexEntity_resourceType_index_name_index_from_index_to`",
        )
        c.executeSQL(
          "DROP INDEX IF EXISTS `index_DateIndexEntity_resourceType_index_name_index_from_index_to`",
        )
        c.executeSQL(
          "DROP INDEX IF EXISTS `index_TokenIndexEntity_resourceType_index_name_index_system_index_value`",
        )
      }

    val MIGRATION_4_5 =
      Migration(4, 5) { c ->
        c.executeSQL(
          "ALTER TABLE `ResourceEntity` ADD COLUMN `lastUpdatedLocal` INTEGER DEFAULT NULL",
        )
      }

    val MIGRATION_5_6 =
      Migration(5, 6) { c ->
        c.executeSQL(
          "CREATE TABLE IF NOT EXISTS `_new_LocalChangeEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceType` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `type` INTEGER NOT NULL, `payload` TEXT NOT NULL, `versionId` TEXT)",
        )
        c.executeSQL(
          "INSERT INTO `_new_LocalChangeEntity` (`id`,`resourceType`,`resourceId`,`timestamp`,`type`,`payload`,`versionId`) SELECT `id`,`resourceType`,`resourceId`, COALESCE(strftime('%s', `timestamp`) || substr(strftime('%f', `timestamp`), 4), 0),`type`,`payload`,`versionId` FROM `LocalChangeEntity`",
        )
        c.executeSQL("DROP TABLE `LocalChangeEntity`")
        c.executeSQL("ALTER TABLE `_new_LocalChangeEntity` RENAME TO `LocalChangeEntity`")
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_LocalChangeEntity_resourceType_resourceId` ON `LocalChangeEntity` (`resourceType`, `resourceId`)",
        )
      }

    val MIGRATION_6_7 =
      Migration(6, 7) { c ->
        c.executeSQL(
          "CREATE TABLE IF NOT EXISTS `_new_LocalChangeEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceType` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `resourceUuid` BLOB NOT NULL, `timestamp` INTEGER NOT NULL, `type` INTEGER NOT NULL, `payload` TEXT NOT NULL, `versionId` TEXT)",
        )
        c.executeSQL(
          "INSERT INTO `_new_LocalChangeEntity` (`id`,`resourceType`,`resourceId`,`resourceUuid`,`timestamp`,`type`,`payload`,`versionId`) SELECT localChange.id, localChange.resourceType, localChange.resourceId, resource.resourceUuid, localChange.timestamp, localChange.type, localChange.payload, localChange.versionId FROM `LocalChangeEntity` localChange LEFT JOIN ResourceEntity resource ON localChange.resourceId = resource.resourceId",
        )
        c.executeSQL("DROP TABLE `LocalChangeEntity`")
        c.executeSQL("ALTER TABLE `_new_LocalChangeEntity` RENAME TO `LocalChangeEntity`")
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_LocalChangeEntity_resourceType_resourceId` ON `LocalChangeEntity` (`resourceType`, `resourceId`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_LocalChangeEntity_resourceUuid` ON `LocalChangeEntity` (`resourceUuid`)",
        )
      }

    val MIGRATION_7_8 =
      Migration(7, 8) { c ->
        c.executeSQL(
          "CREATE TABLE IF NOT EXISTS `LocalChangeResourceReferenceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `localChangeId` INTEGER NOT NULL, `resourceReferenceValue` TEXT NOT NULL, `resourceReferencePath` TEXT, FOREIGN KEY(`localChangeId`) REFERENCES `LocalChangeEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_LocalChangeResourceReferenceEntity_resourceReferenceValue` ON `LocalChangeResourceReferenceEntity` (`resourceReferenceValue`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_LocalChangeResourceReferenceEntity_localChangeId` ON `LocalChangeResourceReferenceEntity` (`localChangeId`)",
        )
        val references = mutableListOf<Pair<Long, String>>()
        c.prepare("SELECT id, type, payload FROM LocalChangeEntity").use { statement ->
          while (statement.step()) {
            val id = statement.getLong(0)
            val payload = statement.getText(2)
            val values =
              when (LocalChangeEntity.Type.from(statement.getInt(1))) {
                LocalChangeEntity.Type.INSERT ->
                  extractAllValuesWithKey("reference", Json.parseToJsonElement(payload))
                LocalChangeEntity.Type.UPDATE ->
                  Json.parseToJsonElement(payload).jsonArray.flatMap { operation ->
                    extractAllValuesWithKey("reference", operation) +
                      listOfNotNull(lookForReferencesInJsonPatch(operation.jsonObject))
                  }
                LocalChangeEntity.Type.DELETE -> emptyList()
              }
            values.forEach { references.add(id to it) }
          }
        }
        references.forEach { (id, value) ->
          c.prepare(
              "INSERT INTO LocalChangeResourceReferenceEntity (localChangeId, resourceReferenceValue) VALUES (?, ?)",
            )
            .use {
              it.bindLong(1, id)
              it.bindText(2, value)
              it.step()
            }
        }
      }

    val MIGRATION_8_9 =
      Migration(8, 9) { c ->
        c.executeSQL(
          "DROP INDEX IF EXISTS `index_TokenIndexEntity_resourceType_index_name_index_system_index_value_resourceUuid`",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_TokenIndexEntity_resourceType_index_name_index_value_resourceUuid` ON `TokenIndexEntity` (`resourceType`, `index_name`, `index_value`, `resourceUuid`)",
        )
      }

    val MIGRATION_9_10 =
      Migration(9, 10) { c ->
        c.executeSQL("DROP INDEX IF EXISTS `index_DateIndexEntity_resourceUuid`")
        c.executeSQL("DROP INDEX IF EXISTS `index_DateTimeIndexEntity_resourceUuid`")
        c.executeSQL("DROP INDEX IF EXISTS `index_NumberIndexEntity_resourceUuid`")
        c.executeSQL("DROP INDEX IF EXISTS `index_StringIndexEntity_resourceUuid`")
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_DateIndexEntity_resourceUuid_index_name_index_from` ON `DateIndexEntity` (`resourceUuid`, `index_name`, `index_from`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_DateTimeIndexEntity_resourceUuid_index_name_index_from` ON `DateTimeIndexEntity` (`resourceUuid`, `index_name`, `index_from`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_NumberIndexEntity_resourceUuid_index_name_index_value` ON `NumberIndexEntity` (`resourceUuid`, `index_name`, `index_value`)",
        )
        c.executeSQL(
          "CREATE INDEX IF NOT EXISTS `index_StringIndexEntity_resourceUuid_index_name_index_value` ON `StringIndexEntity` (`resourceUuid`, `index_name`, `index_value`)",
        )
      }

    /** Schema 10 and 11 are identical, so this only lets Room record the new version. */
    val MIGRATION_10_11 = Migration(10, 11) {}

    val MIGRATIONS =
      arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
      )
  }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object ResourceDatabaseConstructor : RoomDatabaseConstructor<ResourceDatabase> {
  override fun initialize(): ResourceDatabase
}
