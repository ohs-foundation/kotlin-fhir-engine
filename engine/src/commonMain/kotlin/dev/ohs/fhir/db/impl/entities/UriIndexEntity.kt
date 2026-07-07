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
package dev.ohs.fhir.db.impl.entities

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.ohs.fhir.index.entities.UriIndex
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.uuid.Uuid

@Entity(
  indices =
    [
      Index(value = ["resourceType", "index_name", "index_value"]),
      // keep this index for faster foreign lookup
      Index(value = ["resourceUuid"]),
    ],
  foreignKeys =
    [
      ForeignKey(
        entity = ResourceEntity::class,
        parentColumns = ["resourceUuid"],
        childColumns = ["resourceUuid"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.NO_ACTION,
        deferred = true,
      ),
    ],
)
internal data class UriIndexEntity(
  @PrimaryKey(autoGenerate = true) val id: Long,
  val resourceUuid: Uuid,
  val resourceType: ResourceType,
  @Embedded(prefix = "index_") val index: UriIndex,
)
