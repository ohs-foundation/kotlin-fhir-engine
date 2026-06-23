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

package dev.ohs.fhir.db.impl.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import co.touchlab.kermit.Logger
import dev.ohs.fhir.LocalChangeToken
import dev.ohs.fhir.db.impl.JsonDiff
import dev.ohs.fhir.db.impl.addUpdatedReferenceToResource
import dev.ohs.fhir.db.impl.entities.LocalChangeEntity
import dev.ohs.fhir.db.impl.entities.LocalChangeEntity.Type
import dev.ohs.fhir.db.impl.entities.LocalChangeResourceReferenceEntity
import dev.ohs.fhir.db.impl.entities.ResourceEntity
import dev.ohs.fhir.db.impl.fhirJsonParser
import dev.ohs.fhir.db.impl.replaceJsonValue
import dev.ohs.fhir.resourceTypeEnum
import dev.ohs.fhir.versionId
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Dao for local changes made to a resource. One row in LocalChangeEntity corresponds to one change
 * e.g. an INSERT or UPDATE. The UPDATES (diffs) are stored as RFC 6902 JSON patches.
 */
@Dao
internal abstract class LocalChangeDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun addLocalChange(localChangeEntity: LocalChangeEntity): Long

  @Query(
    """
        UPDATE LocalChangeEntity
        SET resourceId = :updatedResourceId
        WHERE id = :localChangeId
    """,
  )
  abstract suspend fun updateResourceId(localChangeId: Long, updatedResourceId: String): Int

  @Transaction
  open suspend fun addInsert(resource: Resource, resourceUuid: Uuid, timeOfLocalChange: Instant) {
    val resourceId = resource.id.orEmpty()
    val resourceType = resource.resourceTypeEnum
    val resourceString = fhirJsonParser.encodeToString(resource)

    val localChangeEntity =
      LocalChangeEntity(
        id = DEFAULT_ID_VALUE,
        resourceType = resourceType.name,
        resourceId = resourceId,
        resourceUuid = resourceUuid,
        timestamp = timeOfLocalChange,
        type = Type.INSERT.value,
        payload = resourceString,
        versionId = resource.versionId,
      )

    val localChangeReferences =
      extractResourceReferences(resource).map { resourceReferenceInfo ->
        LocalChangeResourceReferenceEntity(
          id = DEFAULT_ID_VALUE,
          localChangeId = DEFAULT_ID_VALUE,
          resourceReferencePath = resourceReferenceInfo.name,
          resourceReferenceValue = resourceReferenceInfo.referenceValue,
        )
      }
    createLocalChange(localChangeEntity, localChangeReferences)
  }

  private suspend fun createLocalChange(
    localChange: LocalChangeEntity,
    localChangeReferences: List<LocalChangeResourceReferenceEntity>,
  ) {
    val localChangeId = addLocalChange(localChange)
    if (localChangeReferences.isNotEmpty()) {
      insertLocalChangeResourceReferences(
        localChangeReferences.map { it.copy(localChangeId = localChangeId) },
      )
    }
  }

  suspend fun addUpdate(
    oldEntity: ResourceEntity,
    updatedResource: Resource,
    timeOfLocalChange: Instant,
  ) {
    val resourceId = updatedResource.id.orEmpty()
    val resourceType = updatedResource.resourceTypeEnum

    if (
      !localChangeIsEmpty(resourceId, resourceType) &&
        lastChangeType(resourceId, resourceType)!! == Type.DELETE.value
    ) {
      throw InvalidLocalChangeException(
        "Unexpected DELETE when updating ${resourceType.name}/$resourceId. UPDATE failed.",
      )
    }
    val newSerializedResource = fhirJsonParser.encodeToString(updatedResource)
    val jsonDiff = diff(oldEntity.serializedResource, newSerializedResource)
    if (jsonDiff == EMPTY_JSON_ARRAY) {
      Logger.i {
        "New resource ${resourceType.name}/$resourceId is same as old resource. " +
          "Not inserting UPDATE LocalChange."
      }
      return
    }
    val localChangeEntity =
      LocalChangeEntity(
        id = DEFAULT_ID_VALUE,
        resourceType = resourceType.name,
        resourceId = resourceId,
        resourceUuid = oldEntity.resourceUuid,
        timestamp = timeOfLocalChange,
        type = Type.UPDATE.value,
        payload = jsonDiff,
        versionId = oldEntity.versionId,
      )

    val oldResource = fhirJsonParser.decodeFromString(oldEntity.serializedResource)
    val localChangeReferences =
      extractReferencesDiff(oldResource, updatedResource).map { resourceReferenceInfo ->
        LocalChangeResourceReferenceEntity(
          id = DEFAULT_ID_VALUE,
          localChangeId = DEFAULT_ID_VALUE,
          resourceReferencePath = resourceReferenceInfo.name,
          resourceReferenceValue = resourceReferenceInfo.referenceValue,
        )
      }
    createLocalChange(localChangeEntity, localChangeReferences)
  }

  suspend fun addDelete(
    resourceId: String,
    resourceUuid: Uuid,
    resourceType: ResourceType,
    remoteVersionId: String?,
  ) {
    createLocalChange(
      LocalChangeEntity(
        id = DEFAULT_ID_VALUE,
        resourceType = resourceType.name,
        resourceId = resourceId,
        resourceUuid = resourceUuid,
        timestamp = Clock.System.now(),
        type = Type.DELETE.value,
        payload = "",
        versionId = remoteVersionId,
      ),
      emptyList(),
    )
  }

  /**
   * Extracts the resource references in [resource]. KMP replacement for HAPI's `FhirTerser`: walks
   * the serialized FHIR JSON and collects every object carrying a `reference` string value.
   */
  private fun extractResourceReferences(resource: Resource): Set<ResourceReferenceInfo> {
    val references = mutableListOf<ResourceReferenceInfo>()
    val element =
      runCatching { Json.parseToJsonElement(fhirJsonParser.encodeToString(resource)) }.getOrNull()
        ?: return emptySet()
    collectReferences(path = "", element = element, references = references)
    return references.toSet()
  }

  private fun collectReferences(
    path: String,
    element: JsonElement,
    references: MutableList<ResourceReferenceInfo>,
  ) {
    when (element) {
      is JsonObject -> {
        val referenceValue = element["reference"]
        if (referenceValue is JsonPrimitive && referenceValue.isString) {
          references.add(
            ResourceReferenceInfo(name = path, referenceValue = referenceValue.content),
          )
        }
        element.forEach { (key, value) -> collectReferences("$path/$key", value, references) }
      }
      is JsonArray ->
        element.forEachIndexed { index, value ->
          collectReferences("$path/$index", value, references)
        }
      else -> {}
    }
  }

  /**
   * Extract the difference in the [ResourceReferenceInfo]s in the two versions of the resource.
   *
   * Two versions of a resource can vary in two ways in terms of the resources they refer:
   * 1) A reference present in oldVersionResource is removed, hence, not present in
   *    newVersionResource.
   * 2) A new reference is added to the oldVersionResource, hence, the reference is present in
   *    newVersionResource and not in oldVersionResource.
   *
   * We compute the differences of both the above kinds to return the entire set of differences.
   *
   * This method is useful to extract differences for UPDATE kind of [LocalChange]
   *
   * @param oldVersionResource: The older version of the resource
   * @param newVersionResource: The new version of the resource
   * @return A set of [ResourceReferenceInfo] containing the differences in references between the
   *   two resource versions.
   */
  private fun extractReferencesDiff(
    oldVersionResource: Resource,
    newVersionResource: Resource,
  ): Set<ResourceReferenceInfo> {
    require(oldVersionResource.resourceTypeEnum == newVersionResource.resourceTypeEnum)
    val oldVersionResourceReferences = extractResourceReferences(oldVersionResource).toSet()
    val newVersionResourceReferences = extractResourceReferences(newVersionResource).toSet()
    return (oldVersionResourceReferences - newVersionResourceReferences) +
      (newVersionResourceReferences - oldVersionResourceReferences)
  }

  @Query(
    """
        SELECT type
        FROM LocalChangeEntity
        WHERE resourceId = :resourceId
        AND resourceType = :resourceType
        ORDER BY id ASC
        LIMIT 1
    """,
  )
  abstract suspend fun lastChangeType(resourceId: String, resourceType: ResourceType): Int?

  @Query(
    """
        SELECT COUNT(type)
        FROM LocalChangeEntity
        WHERE resourceId = :resourceId
        AND resourceType = :resourceType
        LIMIT 1
    """,
  )
  abstract suspend fun countLastChange(resourceId: String, resourceType: ResourceType): Int

  private suspend fun localChangeIsEmpty(resourceId: String, resourceType: ResourceType): Boolean =
    countLastChange(resourceId, resourceType) == 0

  @Query(
    """
        SELECT *
        FROM LocalChangeEntity
        ORDER BY timestamp ASC""",
  )
  abstract suspend fun getAllLocalChanges(): List<LocalChangeEntity>

  @Query(
    """
        SELECT *
        FROM LocalChangeEntity
        WHERE LocalChangeEntity.id IN (:ids)
        ORDER BY timestamp ASC""",
  )
  abstract suspend fun getLocalChanges(ids: List<Long>): List<LocalChangeEntity>

  @Query(
    """
        SELECT COUNT(*)
        FROM LocalChangeEntity
        """,
  )
  abstract suspend fun getLocalChangesCount(): Int

  @Query(
    """
        DELETE FROM LocalChangeEntity
        WHERE LocalChangeEntity.id = (:id)
    """,
  )
  abstract suspend fun discardLocalChange(id: Long)

  @Transaction
  open suspend fun discardLocalChanges(token: LocalChangeToken) {
    token.ids.forEach { discardLocalChange(it) }
  }

  @Query(
    """
        DELETE FROM LocalChangeEntity
        WHERE resourceId = (:resourceId)
        AND resourceType = :resourceType
    """,
  )
  abstract suspend fun discardLocalChanges(resourceId: String, resourceType: ResourceType)

  suspend fun discardLocalChanges(resources: List<Resource>) {
    resources.forEach { discardLocalChanges(it.id.orEmpty(), it.resourceTypeEnum) }
  }

  @Query(
    """
        SELECT *
        FROM LocalChangeEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType
        ORDER BY timestamp ASC
    """,
  )
  abstract suspend fun getLocalChanges(
    resourceType: ResourceType,
    resourceId: String,
  ): List<LocalChangeEntity>

  @Query(
    """
        SELECT *
        FROM LocalChangeEntity
        WHERE resourceUuid = :resourceUuid
        ORDER BY timestamp ASC
    """,
  )
  abstract suspend fun getLocalChanges(
    resourceUuid: Uuid,
  ): List<LocalChangeEntity>

  @Query(
    """
        SELECT DISTINCT localChangeId
        FROM LocalChangeResourceReferenceEntity
        WHERE resourceReferenceValue = :resourceReferenceValue
    """,
  )
  abstract suspend fun getLocalChangeIdsWithReferenceValue(
    resourceReferenceValue: String,
  ): List<Long>

  @Query(
    """
        SELECT *
        FROM LocalChangeResourceReferenceEntity
        WHERE localChangeId = :localChangeId
    """,
  )
  abstract suspend fun getReferencesForLocalChange(
    localChangeId: Long,
  ): List<LocalChangeResourceReferenceEntity>

  @Query(
    """
        SELECT *
        FROM LocalChangeResourceReferenceEntity
        WHERE localChangeId IN (:localChangeId)
    """,
  )
  abstract suspend fun getReferencesForLocalChanges(
    localChangeId: List<Long>,
  ): List<LocalChangeResourceReferenceEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertLocalChangeResourceReferences(
    resourceReferences: List<LocalChangeResourceReferenceEntity>,
  )

  /**
   * Updates the [LocalChangeEntity]s to reflect the change in the resource ID.
   *
   * This function performs the following steps:
   * 1. Updates the `resourceId` in `LocalChange` entities directly related to the updated resource
   * 2. Updates references within `LocalChange` payloads that point to the updated resource
   *
   * @param resourceUuid The UUID of the resource whose ID has changed
   * @param oldResource The original resource with the old ID
   * @param updatedResourceId The updated resource ID
   * @return A list of UUIDs representing resources that reference the affected resource
   */
  suspend fun updateResourceIdAndReferences(
    resourceUuid: Uuid,
    oldResource: Resource,
    updatedResourceId: String,
  ): List<Uuid> {
    updateResourceIdInResourceLocalChanges(
      resourceUuid = resourceUuid,
      updatedResourceId = updatedResourceId,
    )
    return updateReferencesInLocalChange(
      oldResource = oldResource,
      updatedResourceId = updatedResourceId,
    )
  }

  /**
   * Updates the [LocalChangeEntity]s for the updated resource by updating the
   * [LocalChangeEntity.resourceId].
   */
  private suspend fun updateResourceIdInResourceLocalChanges(
    resourceUuid: Uuid,
    updatedResourceId: String,
  ) =
    getLocalChanges(resourceUuid).forEach { localChangeEntity ->
      updateResourceId(localChangeEntity.id, updatedResourceId)
    }

  /**
   * Updates references within [LocalChangeEntity] payloads to reflect a resource ID change.
   *
   * This function performs the following steps:
   * 1. Retrieves [LocalChangeEntity] records that reference the old resource.
   * 2. For each [LocalChangeEntity]:
   *     - Replaces the old resource reference with the new one in its payload.
   *     - Creates updated [LocalChangeResourceReferenceEntity] objects.
   *     - Deletes the original [LocalChangeEntity] record, which triggers a cascading delete in
   *       [LocalChangeResourceReferenceEntity].
   *     - Creates a new [LocalChangeEntity] record along with new
   *       [LocalChangeResourceReferenceEntity] records.
   *
   * @param oldResource The original resource whose ID has been updated.
   * @param updatedResourceId The updated resource with the new ID.
   * @return A list of distinct resource UUIDs for all `LocalChangeEntity` records that referenced
   *   the old resource.
   */
  internal suspend fun updateReferencesInLocalChange(
    oldResource: Resource,
    updatedResourceId: String,
  ): List<Uuid> {
    val oldReferenceValue = "${oldResource.resourceTypeEnum.name}/${oldResource.id.orEmpty()}"
    val updatedReferenceValue = "${oldResource.resourceTypeEnum.name}/$updatedResourceId"

    /**
     * [getLocalChangeIdsWithReferenceValue] and [getLocalChanges] cannot be combined due to a
     * limitation in Room. Fetching [LocalChangeEntity] in chunks is required to avoid the error
     * documented in https://github.com/google/android-fhir/issues/2559.
     */
    val referringLocalChangeIds = getLocalChangeIdsWithReferenceValue(oldReferenceValue)
    val localChangeEntitiesWithOldReferences =
      referringLocalChangeIds.chunked(SQLITE_LIMIT_MAX_VARIABLE_NUMBER).flatMap {
        getLocalChanges(it)
      }

    localChangeEntitiesWithOldReferences.forEach { localChangeEntityWithOldReferences ->
      val updatedLocalChangeEntity =
        replaceReferencesInLocalChangePayload(
            localChange = localChangeEntityWithOldReferences,
            oldReference = oldReferenceValue,
            updatedReference = updatedReferenceValue,
          )
          .copy(id = DEFAULT_ID_VALUE)
      val updatedLocalChangeReferences =
        getReferencesForLocalChange(localChangeEntityWithOldReferences.id).map {
          localChangeResourceReferenceEntity ->
          if (localChangeResourceReferenceEntity.resourceReferenceValue == oldReferenceValue) {
            LocalChangeResourceReferenceEntity(
              id = DEFAULT_ID_VALUE,
              localChangeId = DEFAULT_ID_VALUE,
              resourceReferencePath = localChangeResourceReferenceEntity.resourceReferencePath,
              resourceReferenceValue = updatedReferenceValue,
            )
          } else {
            localChangeResourceReferenceEntity.copy(
              id = DEFAULT_ID_VALUE,
              localChangeId = DEFAULT_ID_VALUE,
            )
          }
        }
      discardLocalChange(localChangeEntityWithOldReferences.id)
      createLocalChange(updatedLocalChangeEntity, updatedLocalChangeReferences)
    }
    return localChangeEntitiesWithOldReferences.map { it.resourceUuid }.distinct()
  }

  private fun replaceReferencesInLocalChangePayload(
    localChange: LocalChangeEntity,
    oldReference: String,
    updatedReference: String,
  ): LocalChangeEntity {
    return when (localChange.type) {
      Type.INSERT.value -> {
        val insertResourcePayload = fhirJsonParser.decodeFromString(localChange.payload)
        val updatedResourcePayload =
          addUpdatedReferenceToResource(
            insertResourcePayload,
            oldReference,
            updatedReference,
          )
        return localChange.copy(
          payload = fhirJsonParser.encodeToString(updatedResourcePayload),
        )
      }
      Type.UPDATE.value -> {
        val patchArray = Json.parseToJsonElement(localChange.payload).jsonArray
        val updatedPatchArray =
          JsonArray(patchArray.map { replaceJsonValue(it, oldReference, updatedReference) })
        return localChange.copy(
          payload = updatedPatchArray.toString(),
        )
      }
      Type.DELETE.value -> localChange
      else -> error("Unexpected LocalChange type: ${localChange.type}")
    }
  }

  @Query(
    """
    SELECT *
      FROM LocalChangeEntity
      WHERE resourceUuid = (
        SELECT resourceUuid
        FROM LocalChangeEntity
        ORDER BY timestamp ASC
        LIMIT 1)
      ORDER BY timestamp ASC
    """,
  )
  abstract suspend fun getAllChangesForEarliestChangedResource(): List<LocalChangeEntity>

  class InvalidLocalChangeException(message: String?) : Exception(message)

  /**
   * KMP replacement for HAPI's `FhirTerser` [ca.uhn.fhir.util.ResourceReferenceInfo]: a (JSON
   * pointer path, reference value) pair extracted from a serialized FHIR resource.
   */
  private data class ResourceReferenceInfo(val name: String, val referenceValue: String)

  companion object {
    const val DEFAULT_ID_VALUE = 0L

    private const val EMPTY_JSON_ARRAY = "[]"

    /**
     * Represents SQLite limit on the size of parameters that can be passed in an IN(..) query See
     * https://issuetracker.google.com/issues/192284727 See https://www.sqlite.org/limits.html See
     * https://github.com/google/android-fhir/issues/2559
     */
    const val SQLITE_LIMIT_MAX_VARIABLE_NUMBER = 999
  }
}

/**
 * Calculates the JSON patch between two serialized [Resource]s. KMP replacement for the original
 * engine's Jackson + jsonpatch `diff`; delegates to [JsonDiff], which also filters the `/meta` and
 * `/text` paths.
 */
internal fun diff(source: String, target: String): String = JsonDiff.diff(source, target)
