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
package dev.ohs.fhir.engine.db.impl.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import dev.ohs.fhir.engine.db.ResourceNotFoundException
import dev.ohs.fhir.engine.db.impl.entities.DateIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.DateTimeIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.NumberIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.PositionIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.QuantityIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.ReferenceIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.ResourceEntity
import dev.ohs.fhir.engine.db.impl.entities.StringIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.TokenIndexEntity
import dev.ohs.fhir.engine.db.impl.entities.UriIndexEntity
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.ResourceIndexer.Companion.createLocalLastUpdatedIndex
import dev.ohs.fhir.engine.index.ResourceIndices
import dev.ohs.fhir.engine.lastUpdated
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Instant as FhirInstant
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.engine.resourceTypeEnum
import dev.ohs.fhir.engine.updateMeta
import dev.ohs.fhir.engine.versionId
import dev.ohs.fhir.engine.withId
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
internal abstract class ResourceDao {
  /**
   * This is ugly but there is no way to inject these right now in Room as it is the one creating
   * the dao.
   *
   * TODO: https://github.com/ohs-foundation/kotlin-fhir-engine/issues/57
   */
  lateinit var resourceIndexer: ResourceIndexer

  /**
   * Updates the resource in the [ResourceEntity] and adds indexes as a result of changes made on
   * device.
   *
   * @param [resource] the resource with local (on device) updates
   * @param [timeOfLocalChange] time when the local change was made
   */
  suspend fun applyLocalUpdate(resource: Resource, timeOfLocalChange: Instant?) {
    getResourceEntity(resource.id.orEmpty(), resource.resourceTypeEnum)?.let {
      val entity =
        it.copy(
          serializedResource = fhirJsonParser.encodeToString(resource),
          lastUpdatedLocal = timeOfLocalChange,
          lastUpdatedRemote = resource.lastUpdated ?: it.lastUpdatedRemote,
        )
      updateChanges(entity, resource)
    }
      ?: throw ResourceNotFoundException(resource.resourceTypeEnum.name, resource.id.orEmpty())
  }

  suspend fun updateResourceWithUuid(resourceUuid: Uuid, updatedResource: Resource) {
    getResourceEntity(resourceUuid)?.let {
      val entity =
        it.copy(
          resourceId = updatedResource.id.orEmpty(),
          serializedResource = fhirJsonParser.encodeToString(updatedResource),
          lastUpdatedRemote = updatedResource.lastUpdated ?: it.lastUpdatedRemote,
          versionId = updatedResource.versionId ?: it.versionId,
        )
      updateChanges(entity, updatedResource)
    }
      ?: throw ResourceNotFoundException(resourceUuid)
  }

  /**
   * Updates the resource in the [ResourceEntity] and adds indexes as a result of downloading the
   * resource from server.
   *
   * @param [resource] the resource with the remote(server) updates
   */
  private suspend fun applyRemoteUpdate(resource: Resource) {
    getResourceEntity(resource.id.orEmpty(), resource.resourceTypeEnum)?.let {
      val entity =
        it.copy(
          serializedResource = fhirJsonParser.encodeToString(resource),
          lastUpdatedRemote = resource.lastUpdated,
          versionId = resource.versionId,
        )
      updateChanges(entity, resource)
    }
      ?: throw ResourceNotFoundException(resource.resourceTypeEnum.name, resource.id.orEmpty())
  }

  private suspend fun updateChanges(entity: ResourceEntity, resource: Resource) {
    // The foreign key in Index entity tables is set with cascade delete constraint and
    // insertResource has REPLACE conflict resolution. So, when we do an insert to update the
    // resource, it deletes old resource and corresponding index entities (based on foreign key
    // constrain) before inserting the new resource.
    insertResource(entity)
    val index =
      ResourceIndices.Builder(resourceIndexer.index(resource))
        .apply {
          entity.lastUpdatedLocal?.let { instant ->
            addDateTimeIndex(
              createLocalLastUpdatedIndex(
                resource.resourceTypeEnum,
                FhirInstant(value = FhirDateTime.fromString(instant.toString())),
              ),
            )
          }
        }
        .build()
    updateIndicesForResource(index, resource.resourceTypeEnum, entity.resourceUuid)
  }

  suspend fun insertAllRemote(resources: List<Resource>): List<Uuid> {
    return resources.map { resource -> insertRemoteResource(resource) }
  }

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertResource(resource: ResourceEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertStringIndex(stringIndexEntity: StringIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertReferenceIndex(referenceIndexEntity: ReferenceIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertCodeIndex(tokenIndexEntity: TokenIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertQuantityIndex(quantityIndexEntity: QuantityIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertUriIndex(uriIndexEntity: UriIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertDateIndex(dateIndexEntity: DateIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertDateTimeIndex(dateTimeIndexEntity: DateTimeIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertNumberIndex(numberIndexEntity: NumberIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertPositionIndex(positionIndexEntity: PositionIndexEntity)

  @Query(
    """
        UPDATE ResourceEntity
        SET versionId = :versionId,
            lastUpdatedRemote = :lastUpdatedRemote
        WHERE resourceId = :resourceId
        AND resourceType = :resourceType
    """,
  )
  abstract suspend fun updateRemoteVersionIdAndLastUpdate(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String?,
    lastUpdatedRemote: Instant?,
  )

  @Query(
    """
        DELETE FROM ResourceEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType""",
  )
  abstract suspend fun deleteResource(resourceId: String, resourceType: ResourceType): Int

  @Query(
    """
        SELECT serializedResource
        FROM ResourceEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType""",
  )
  abstract suspend fun getResource(resourceId: String, resourceType: ResourceType): String?

  @Query(
    """
        SELECT *
        FROM ResourceEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType
    """,
  )
  abstract suspend fun getResourceEntity(
    resourceId: String,
    resourceType: ResourceType,
  ): ResourceEntity?

  @Query(
    """
        SELECT *
        FROM ResourceEntity
        WHERE resourceUuid = :resourceUuid
    """,
  )
  abstract suspend fun getResourceEntity(resourceUuid: Uuid): ResourceEntity?

  @RawQuery abstract suspend fun getResources(query: RoomRawQuery): List<SerializedResourceWithUuid>

  @RawQuery
  abstract suspend fun getForwardReferencedResources(
    query: RoomRawQuery,
  ): List<ForwardIncludeSearchResponse>

  @RawQuery
  abstract suspend fun getReverseReferencedResources(
    query: RoomRawQuery,
  ): List<ReverseIncludeSearchResponse>

  @RawQuery abstract suspend fun countResources(query: RoomRawQuery): Long

  suspend fun insertLocalResource(resource: Resource, timeOfChange: Instant) =
    insertResource(resource, timeOfChange)

  private suspend fun insertRemoteResource(resource: Resource): Uuid {
    val existingResourceEntity = getResourceEntity(resource.id.orEmpty(), resource.resourceTypeEnum)
    if (existingResourceEntity != null) {
      applyRemoteUpdate(resource)
      return existingResourceEntity.resourceUuid
    }
    return insertResource(resource, null)
  }

  private suspend fun insertResource(resource: Resource, lastUpdatedLocal: Instant?): Uuid {
    val resourceUuid = Uuid.random()

    // Use the local UUID as the logical ID of the resource
    val resourceWithId =
      if (resource.id.isNullOrEmpty()) resource.withId(resourceUuid.toString()) else resource

    val entity =
      ResourceEntity(
        id = 0,
        resourceType = resourceWithId.resourceTypeEnum,
        resourceUuid = resourceUuid,
        resourceId = resourceWithId.id.orEmpty(),
        serializedResource = fhirJsonParser.encodeToString(resourceWithId),
        versionId = resourceWithId.versionId,
        lastUpdatedRemote = resourceWithId.lastUpdated,
        lastUpdatedLocal = lastUpdatedLocal,
      )
    insertResource(entity)

    val index =
      ResourceIndices.Builder(resourceIndexer.index(resourceWithId))
        .apply {
          lastUpdatedLocal?.let {
            addDateTimeIndex(
              createLocalLastUpdatedIndex(
                entity.resourceType,
                FhirInstant(value = FhirDateTime.fromString(it.toString())),
              ),
            )
          }
        }
        .build()

    updateIndicesForResource(index, resourceWithId.resourceTypeEnum, resourceUuid)

    return entity.resourceUuid
  }

  suspend fun updateAndIndexRemoteVersionIdAndLastUpdate(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String?,
    lastUpdatedRemote: Instant?,
  ) {
    getResourceEntity(resourceId, resourceType)?.let { oldResourceEntity ->
      val resource = fhirJsonParser.decodeFromString<Resource>(oldResourceEntity.serializedResource)
      val updated = resource.updateMeta(versionId, lastUpdatedRemote)
      updateResourceWithUuid(oldResourceEntity.resourceUuid, updated)
    }
  }

  private suspend fun updateIndicesForResource(
    index: ResourceIndices,
    resourceType: ResourceType,
    resourceUuid: Uuid,
  ) {
    // TODO Move StringIndices to persistable types
    //  https://github.com/jingtang10/fhir-engine/issues/31
    //  we can either use room-autovalue integration or go w/ embedded data classes.
    //  we may also want to merge them:
    //  https://github.com/jingtang10/fhir-engine/issues/33
    index.stringIndices.forEach {
      insertStringIndex(
        StringIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.referenceIndices.forEach {
      insertReferenceIndex(
        ReferenceIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.tokenIndices.forEach {
      insertCodeIndex(
        TokenIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.quantityIndices.forEach {
      insertQuantityIndex(
        QuantityIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.uriIndices.forEach {
      insertUriIndex(
        UriIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.dateIndices.forEach {
      insertDateIndex(
        DateIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.dateTimeIndices.forEach {
      insertDateTimeIndex(
        DateTimeIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.numberIndices.forEach {
      insertNumberIndex(
        NumberIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
    index.positionIndices.forEach {
      insertPositionIndex(
        PositionIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        ),
      )
    }
  }
}

internal class ForwardIncludeSearchResponse(
  @ColumnInfo(name = "index_name") val matchingIndex: String,
  @ColumnInfo(name = "resourceUuid") val baseResourceUUID: Uuid,
  val serializedResource: String,
)

internal class ReverseIncludeSearchResponse(
  @ColumnInfo(name = "index_name") val matchingIndex: String,
  @ColumnInfo(name = "index_value") val baseResourceTypeAndId: String,
  val serializedResource: String,
)

/**
 * Data class representing a forward included [Resource], the index on which the match was done and
 * the uuid of the base [Resource] for which this [Resource] has been included.
 */
internal data class ForwardIncludeSearchResult(
  val searchIndex: String,
  val baseResourceUUID: Uuid,
  val resource: Resource,
)

/**
 * Data class representing a reverse included [Resource], the index on which the match was done and
 * the type and logical id of the base [Resource] for which this [Resource] has been included.
 */
internal data class ReverseIncludeSearchResult(
  val searchIndex: String,
  val baseResourceTypeWithId: String,
  val resource: Resource,
)

internal data class SerializedResourceWithUuid(
  @ColumnInfo(name = "resourceUuid") val uuid: Uuid,
  val serializedResource: String,
)
