/*
 * Copyright 2023-2026 Open Health Stack Foundation
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
package dev.ohs.fhir.sync.upload.patch

import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.time.Instant

/**
 * Squashed local changes for a resource pending upload.
 *
 * @property resourceType The [ResourceType].
 * @property resourceId The resource id [Resource.id].
 * @property versionId Server version this change is based on; `null` for new resources.
 * @property timestamp When the user performed the create/update/delete operation.
 * @property type Type of local change.
 * @property payload JSON representation of the change — format depends on [type].
 */
internal data class Patch(
  val resourceType: String,
  val resourceId: String,
  val versionId: String? = null,
  val timestamp: Instant,
  val type: Type,
  val payload: String,
) {
  enum class Type(val value: Int) {
    /** Create a new resource; payload is the full resource JSON. */
    INSERT(1),

    /** Update an existing resource; payload is a JSON Patch document. */
    UPDATE(2),

    /** Delete a resource; payload is empty. */
    DELETE(3),
    ;

    companion object {
      fun from(input: Int): Type = entries.first { it.value == input }
    }
  }
}

internal fun LocalChange.Type.toPatchType(): Patch.Type {
  return when (this) {
    LocalChange.Type.INSERT -> Patch.Type.INSERT
    LocalChange.Type.UPDATE -> Patch.Type.UPDATE
    LocalChange.Type.DELETE -> Patch.Type.DELETE
  }
}
