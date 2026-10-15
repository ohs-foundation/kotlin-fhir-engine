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
/**
 * The table and index definitions of schema version 11, frozen.
 *
 * [AlphaDatabaseConverter] rebuilds the tables of a database from an alpha release to exactly this
 * layout, which is the one those releases already had, so these definitions must not follow later
 * schema versions. An entity change belongs in a new migration, never here. ExportedSchemasTest
 * pins them to the exported 11.json.
 */
internal object Schema11 {
  /** The version these definitions describe. Frozen with them, so it never moves. */
  const val VERSION = 11

  val tables: List<Table> =
    listOf(
      Table(
        "ResourceEntity",
        listOf(
          "id",
          "resourceUuid",
          "resourceType",
          "resourceId",
          "serializedResource",
          "versionId",
          "lastUpdatedRemote",
          "lastUpdatedLocal",
        ),
        """CREATE TABLE IF NOT EXISTS `ResourceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `serializedResource` TEXT NOT NULL, `versionId` TEXT, `lastUpdatedRemote` INTEGER, `lastUpdatedLocal` INTEGER)""",
        listOf(
          """CREATE UNIQUE INDEX IF NOT EXISTS `index_ResourceEntity_resourceUuid` ON `ResourceEntity` (`resourceUuid`)""",
          """CREATE UNIQUE INDEX IF NOT EXISTS `index_ResourceEntity_resourceType_resourceId` ON `ResourceEntity` (`resourceType`, `resourceId`)""",
        ),
      ),
      Table(
        "StringIndexEntity",
        listOf("id", "resourceUuid", "resourceType", "index_name", "index_path", "index_value"),
        """CREATE TABLE IF NOT EXISTS `StringIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_value` TEXT NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_StringIndexEntity_resourceType_index_name_index_value` ON `StringIndexEntity` (`resourceType`, `index_name`, `index_value`)""",
          """CREATE INDEX IF NOT EXISTS `index_StringIndexEntity_resourceUuid_index_name_index_value` ON `StringIndexEntity` (`resourceUuid`, `index_name`, `index_value`)""",
        ),
      ),
      Table(
        "ReferenceIndexEntity",
        listOf("id", "resourceUuid", "resourceType", "index_name", "index_path", "index_value"),
        """CREATE TABLE IF NOT EXISTS `ReferenceIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_value` TEXT NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_ReferenceIndexEntity_resourceType_index_name_index_value` ON `ReferenceIndexEntity` (`resourceType`, `index_name`, `index_value`)""",
          """CREATE INDEX IF NOT EXISTS `index_ReferenceIndexEntity_resourceUuid` ON `ReferenceIndexEntity` (`resourceUuid`)""",
        ),
      ),
      Table(
        "TokenIndexEntity",
        listOf(
          "id",
          "resourceUuid",
          "resourceType",
          "index_name",
          "index_path",
          "index_system",
          "index_value",
        ),
        """CREATE TABLE IF NOT EXISTS `TokenIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_system` TEXT, `index_value` TEXT NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_TokenIndexEntity_resourceType_index_name_index_value_resourceUuid` ON `TokenIndexEntity` (`resourceType`, `index_name`, `index_value`, `resourceUuid`)""",
          """CREATE INDEX IF NOT EXISTS `index_TokenIndexEntity_resourceUuid` ON `TokenIndexEntity` (`resourceUuid`)""",
        ),
      ),
      Table(
        "QuantityIndexEntity",
        listOf(
          "id",
          "resourceUuid",
          "resourceType",
          "index_name",
          "index_path",
          "index_system",
          "index_code",
          "index_value",
        ),
        """CREATE TABLE IF NOT EXISTS `QuantityIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_system` TEXT NOT NULL, `index_code` TEXT NOT NULL, `index_value` REAL NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_QuantityIndexEntity_resourceType_index_name_index_value_index_code` ON `QuantityIndexEntity` (`resourceType`, `index_name`, `index_value`, `index_code`)""",
          """CREATE INDEX IF NOT EXISTS `index_QuantityIndexEntity_resourceUuid` ON `QuantityIndexEntity` (`resourceUuid`)""",
        ),
      ),
      Table(
        "UriIndexEntity",
        listOf("id", "resourceUuid", "resourceType", "index_name", "index_path", "index_value"),
        """CREATE TABLE IF NOT EXISTS `UriIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_value` TEXT NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_UriIndexEntity_resourceType_index_name_index_value` ON `UriIndexEntity` (`resourceType`, `index_name`, `index_value`)""",
          """CREATE INDEX IF NOT EXISTS `index_UriIndexEntity_resourceUuid` ON `UriIndexEntity` (`resourceUuid`)""",
        ),
      ),
      Table(
        "DateIndexEntity",
        listOf(
          "id",
          "resourceUuid",
          "resourceType",
          "index_name",
          "index_path",
          "index_from",
          "index_to",
        ),
        """CREATE TABLE IF NOT EXISTS `DateIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_from` INTEGER NOT NULL, `index_to` INTEGER NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_DateIndexEntity_resourceType_index_name_resourceUuid_index_from_index_to` ON `DateIndexEntity` (`resourceType`, `index_name`, `resourceUuid`, `index_from`, `index_to`)""",
          """CREATE INDEX IF NOT EXISTS `index_DateIndexEntity_resourceUuid_index_name_index_from` ON `DateIndexEntity` (`resourceUuid`, `index_name`, `index_from`)""",
        ),
      ),
      Table(
        "DateTimeIndexEntity",
        listOf(
          "id",
          "resourceUuid",
          "resourceType",
          "index_name",
          "index_path",
          "index_from",
          "index_to",
        ),
        """CREATE TABLE IF NOT EXISTS `DateTimeIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_from` INTEGER NOT NULL, `index_to` INTEGER NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_DateTimeIndexEntity_resourceType_index_name_resourceUuid_index_from_index_to` ON `DateTimeIndexEntity` (`resourceType`, `index_name`, `resourceUuid`, `index_from`, `index_to`)""",
          """CREATE INDEX IF NOT EXISTS `index_DateTimeIndexEntity_resourceUuid_index_name_index_from` ON `DateTimeIndexEntity` (`resourceUuid`, `index_name`, `index_from`)""",
        ),
      ),
      Table(
        "NumberIndexEntity",
        listOf("id", "resourceUuid", "resourceType", "index_name", "index_path", "index_value"),
        """CREATE TABLE IF NOT EXISTS `NumberIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_name` TEXT NOT NULL, `index_path` TEXT NOT NULL, `index_value` REAL NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_NumberIndexEntity_resourceType_index_name_index_value` ON `NumberIndexEntity` (`resourceType`, `index_name`, `index_value`)""",
          """CREATE INDEX IF NOT EXISTS `index_NumberIndexEntity_resourceUuid_index_name_index_value` ON `NumberIndexEntity` (`resourceUuid`, `index_name`, `index_value`)""",
        ),
      ),
      Table(
        "PositionIndexEntity",
        listOf("id", "resourceUuid", "resourceType", "index_latitude", "index_longitude"),
        """CREATE TABLE IF NOT EXISTS `PositionIndexEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceUuid` BLOB NOT NULL, `resourceType` TEXT NOT NULL, `index_latitude` REAL NOT NULL, `index_longitude` REAL NOT NULL, FOREIGN KEY(`resourceUuid`) REFERENCES `ResourceEntity`(`resourceUuid`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_PositionIndexEntity_resourceType_index_latitude_index_longitude` ON `PositionIndexEntity` (`resourceType`, `index_latitude`, `index_longitude`)""",
          """CREATE INDEX IF NOT EXISTS `index_PositionIndexEntity_resourceUuid` ON `PositionIndexEntity` (`resourceUuid`)""",
        ),
      ),
      Table(
        "LocalChangeEntity",
        listOf(
          "id",
          "resourceType",
          "resourceId",
          "resourceUuid",
          "timestamp",
          "type",
          "payload",
          "versionId",
        ),
        """CREATE TABLE IF NOT EXISTS `LocalChangeEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resourceType` TEXT NOT NULL, `resourceId` TEXT NOT NULL, `resourceUuid` BLOB NOT NULL, `timestamp` INTEGER NOT NULL, `type` INTEGER NOT NULL, `payload` TEXT NOT NULL, `versionId` TEXT)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_LocalChangeEntity_resourceType_resourceId` ON `LocalChangeEntity` (`resourceType`, `resourceId`)""",
          """CREATE INDEX IF NOT EXISTS `index_LocalChangeEntity_resourceUuid` ON `LocalChangeEntity` (`resourceUuid`)""",
        ),
      ),
      Table(
        "LocalChangeResourceReferenceEntity",
        listOf("id", "localChangeId", "resourceReferenceValue", "resourceReferencePath"),
        """CREATE TABLE IF NOT EXISTS `LocalChangeResourceReferenceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `localChangeId` INTEGER NOT NULL, `resourceReferenceValue` TEXT NOT NULL, `resourceReferencePath` TEXT, FOREIGN KEY(`localChangeId`) REFERENCES `LocalChangeEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)""",
        listOf(
          """CREATE INDEX IF NOT EXISTS `index_LocalChangeResourceReferenceEntity_resourceReferenceValue` ON `LocalChangeResourceReferenceEntity` (`resourceReferenceValue`)""",
          """CREATE INDEX IF NOT EXISTS `index_LocalChangeResourceReferenceEntity_localChangeId` ON `LocalChangeResourceReferenceEntity` (`localChangeId`)""",
        ),
      ),
    )

  /**
   * [columns] serves [hasColumn] only. The converter copies whatever columns the old table really
   * has, read from the file, so a stale list cannot drop data.
   */
  class Table(
    val name: String,
    val columns: List<String>,
    val createSql: String,
    val indexSql: List<String>,
  ) {
    fun hasColumn(column: String) = column in columns
  }
}
