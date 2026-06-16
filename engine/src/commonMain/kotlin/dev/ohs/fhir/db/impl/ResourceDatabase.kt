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

package dev.ohs.fhir.db.impl

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import dev.ohs.fhir.db.impl.dao.LocalChangeDao
import dev.ohs.fhir.db.impl.dao.ResourceDao
import dev.ohs.fhir.db.impl.entities.DateIndexEntity
import dev.ohs.fhir.db.impl.entities.DateTimeIndexEntity
import dev.ohs.fhir.db.impl.entities.LocalChangeEntity
import dev.ohs.fhir.db.impl.entities.LocalChangeResourceReferenceEntity
import dev.ohs.fhir.db.impl.entities.NumberIndexEntity
import dev.ohs.fhir.db.impl.entities.PositionIndexEntity
import dev.ohs.fhir.db.impl.entities.QuantityIndexEntity
import dev.ohs.fhir.db.impl.entities.ReferenceIndexEntity
import dev.ohs.fhir.db.impl.entities.ResourceEntity
import dev.ohs.fhir.db.impl.entities.StringIndexEntity
import dev.ohs.fhir.db.impl.entities.TokenIndexEntity
import dev.ohs.fhir.db.impl.entities.UriIndexEntity

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
  version = 2,
  exportSchema = true,
)
@TypeConverters(DbTypeConverters::class)
@ConstructedBy(ResourceDatabaseConstructor::class)
internal abstract class ResourceDatabase : RoomDatabase() {
  abstract fun resourceDao(): ResourceDao

  abstract fun localChangeDao(): LocalChangeDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object ResourceDatabaseConstructor : RoomDatabaseConstructor<ResourceDatabase> {
  override fun initialize(): ResourceDatabase
}
