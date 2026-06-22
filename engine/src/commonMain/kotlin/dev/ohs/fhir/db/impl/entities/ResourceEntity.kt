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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
  indices =
    [
      Index(value = ["resourceUuid"], unique = true),
      Index(value = ["resourceType", "resourceId"], unique = true),
    ]
)
internal data class ResourceEntity(
  @PrimaryKey(autoGenerate = true) val id: Long,
  val resourceUuid: Uuid,
  val resourceType: ResourceType,
  val resourceId: String,
  val serializedResource: String,
  val versionId: String?,
  val lastUpdatedRemote: Instant?,
  val lastUpdatedLocal: Instant?,
)
