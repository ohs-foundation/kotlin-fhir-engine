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

import androidx.room.PooledConnection
import androidx.room.Transactor
import androidx.room.execSQL
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.ohs.fhir.LocalChange
import dev.ohs.fhir.LocalChangeToken
import dev.ohs.fhir.db.Database
import dev.ohs.fhir.db.LocalChangeResourceReference
import dev.ohs.fhir.db.ResourceNotFoundException
import dev.ohs.fhir.db.ResourceWithUUID
import dev.ohs.fhir.db.impl.dao.ForwardIncludeSearchResult
import dev.ohs.fhir.db.impl.dao.ReverseIncludeSearchResult
import dev.ohs.fhir.db.impl.entities.DateIndexEntity
import dev.ohs.fhir.db.impl.entities.DateTimeIndexEntity
import dev.ohs.fhir.db.impl.entities.NumberIndexEntity
import dev.ohs.fhir.db.impl.entities.PositionIndexEntity
import dev.ohs.fhir.db.impl.entities.QuantityIndexEntity
import dev.ohs.fhir.db.impl.entities.ReferenceIndexEntity
import dev.ohs.fhir.db.impl.entities.ResourceEntity
import dev.ohs.fhir.db.impl.entities.StringIndexEntity
import dev.ohs.fhir.db.impl.entities.TokenIndexEntity
import dev.ohs.fhir.db.impl.entities.UriIndexEntity
import dev.ohs.fhir.index.ResourceIndexer
import dev.ohs.fhir.index.ResourceIndices
import dev.ohs.fhir.resourceType
import dev.ohs.fhir.resourceTypeEnum
import dev.ohs.fhir.search.SearchQuery
import dev.ohs.fhir.toLocalChange
import dev.ohs.fhir.updateMeta
import dev.ohs.fhir.withId
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The implementation for the persistence layer using Room KMP. See docs for
 * [dev.ohs.fhir.db.Database] for the API docs.
 *
 * Note: Room KMP does not provide a `withTransaction` extension; transactions are run via
 * [androidx.room.useWriterConnection] + [androidx.room.Transactor.withTransaction]. DAO calls made
 * inside reuse the writer connection from the coroutine context, so they participate in the
 * transaction.
 */
internal class DatabaseImpl(
  platformContext: Any,
  private val resourceIndexer: ResourceIndexer,
) : Database {

  private companion object {
    private val USER_TABLES_QUERY =
      """
      SELECT name FROM sqlite_master
      WHERE type = 'table'
        AND name NOT LIKE 'sqlite_%'
        AND name != 'room_master_table'
        AND name != 'android_metadata'
      """
        .trimIndent()
  }

  private val db: ResourceDatabase =
    getDatabaseBuilder(platformContext)
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .fallbackToDestructiveMigration(dropAllTables = true)
      .build()

  private val resourceDao by lazy { db.resourceDao().also { it.resourceIndexer = resourceIndexer } }
  private val localChangeDao by lazy { db.localChangeDao() }

  override suspend fun <R : Resource> insert(vararg resource: R): List<String> {
    val logicalIds = mutableListOf<String>()
    withTransaction {
      resource.forEach { res ->
        val resourceId = res.id ?: Uuid.random().toString()
        val resourceUuid = Uuid.random()
        val resourceTypeEnum = res.resourceTypeEnum
        val now = Clock.System.now()

        val resourceWithId = if (res.id == null) res.withId(resourceId) else res

        val serialized = serializeResource(resourceWithId)
        val entity =
          ResourceEntity(
            id = 0,
            resourceUuid = resourceUuid,
            resourceType = resourceTypeEnum,
            resourceId = resourceId,
            serializedResource = serialized,
            versionId = null,
            lastUpdatedRemote = null,
            lastUpdatedLocal = now,
          )
        resourceDao.insertResource(entity)

        val indices = resourceIndexer.index(resourceWithId)
        insertIndices(resourceUuid, resourceTypeEnum, indices)

        localChangeDao.addInsert(resourceWithId, resourceUuid, now)

        logicalIds.add(resourceId)
      }
    }
    return logicalIds
  }

  override suspend fun <R : Resource> insertRemote(vararg resource: R) {
    inTransaction {
      resource.forEach { res ->
        val resourceId = res.id ?: error("Remote resource must have an id")
        val resourceUuid = Uuid.random()
        val resourceTypeEnum = res.resourceTypeEnum
        val now = Clock.System.now()

        val entity =
          ResourceEntity(
            id = 0,
            resourceUuid = resourceUuid,
            resourceType = resourceTypeEnum,
            resourceId = resourceId,
            serializedResource = serializeResource(res),
            versionId = null,
            lastUpdatedRemote = now,
            lastUpdatedLocal = now,
          )
        resourceDao.insertResource(entity)

        val indices = resourceIndexer.index(res)
        insertIndices(resourceUuid, resourceTypeEnum, indices)
      }
    }
  }

  override suspend fun select(type: ResourceType, id: String): Resource {
    val json = resourceDao.getResource(resourceId = id, resourceType = type)
    return if (json != null) {
      deserializeResource(json)
    } else {
      throw ResourceNotFoundException(type.name, id)
    }
  }

  override suspend fun selectEntity(type: ResourceType, id: String): ResourceEntity =
    resourceDao.getResourceEntity(resourceId = id, resourceType = type)
      ?: throw ResourceNotFoundException(type.name, id)

  override suspend fun insertSyncedResources(resources: List<Resource>) {
    inTransaction { insertRemote(*resources.toTypedArray()) }
  }

  override suspend fun withTransaction(block: suspend () -> Unit) {
    inTransaction(block)
  }

  /** Runs [block] in a writer transaction, returning its result. */
  private suspend fun <T> inTransaction(block: suspend () -> T): T =
    db.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
    }

  override fun close() {
    db.close()
  }

  override suspend fun clearDatabase() {
    db.useWriterConnection { transactor ->
      transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { clearAllTables() }
    }
  }

  /**
   * Deletes all rows from every user table. Enumerated from `sqlite_master` because Room KMP's
   * commonMain doesn't expose `clearAllTables()`.
   */
  private suspend fun PooledConnection.clearAllTables() {
    val tables = mutableListOf<String>()
    usePrepared(USER_TABLES_QUERY) { statement ->
      while (statement.step()) {
        tables.add(statement.getText(0))
      }
    }
    tables.forEach { execSQL("DELETE FROM `$it`") }
  }

  override suspend fun update(vararg resources: Resource) {
    inTransaction {
      resources.forEach { res ->
        val resourceId = res.id ?: error("Resource must have an id to be updated")
        val resourceTypeEnum = res.resourceTypeEnum
        val existing =
          resourceDao.getResourceEntity(resourceId = resourceId, resourceType = resourceTypeEnum)
            ?: throw ResourceNotFoundException(resourceTypeEnum.name, resourceId)

        val now = Clock.System.now()

        // Record the local change by diffing the old (still-stored) resource against the new one.
        localChangeDao.addUpdate(existing, res, now)

        val updatedEntity =
          existing.copy(
            serializedResource = serializeResource(res),
            lastUpdatedLocal = now,
          )
        resourceDao.insertResource(updatedEntity)

        val indices = resourceIndexer.index(res)
        insertIndices(existing.resourceUuid, resourceTypeEnum, indices)
      }
    }
  }

  override suspend fun updateVersionIdAndLastUpdated(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String?,
    lastUpdated: Instant?,
  ) {
    resourceDao.updateRemoteVersionIdAndLastUpdate(
      resourceId = resourceId,
      resourceType = resourceType,
      versionId = versionId,
      lastUpdatedRemote = lastUpdated,
    )
  }

  override suspend fun updateResourcePostSync(
    oldResourceId: String,
    newResourceId: String,
    resourceType: ResourceType,
    versionId: String?,
    lastUpdated: Instant?,
  ) {
    inTransaction {
      resourceDao.getResourceEntity(oldResourceId, resourceType)?.let { oldResourceEntity ->
        val updatedResource =
          fhirJsonParser
            .decodeFromString(oldResourceEntity.serializedResource)
            .withId(newResourceId)
            .updateMeta(versionId, lastUpdated)
        updateResourceAndReferences(oldResourceId, updatedResource)
      }
    }
  }

  override suspend fun delete(type: ResourceType, id: String) {
    inTransaction {
      val existing = resourceDao.getResourceEntity(resourceId = id, resourceType = type)
      val rowsDeleted = resourceDao.deleteResource(resourceId = id, resourceType = type)
      if (rowsDeleted > 0 && existing != null) {
        localChangeDao.addDelete(
          resourceId = id,
          resourceUuid = existing.resourceUuid,
          resourceType = type,
          remoteVersionId = existing.versionId,
        )
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  override suspend fun <R : Resource> search(query: SearchQuery): List<ResourceWithUUID<R>> {
    return db.useReaderConnection { transactor ->
      transactor.usePrepared(query.query) { statement ->
        bindArgs(statement, query.args)
        val results = mutableListOf<ResourceWithUUID<R>>()
        while (statement.step()) {
          val uuid = statement.getText(0)
          val json = statement.getText(1)
          val resource = deserializeResource(json) as R
          results.add(ResourceWithUUID(Uuid.parse(uuid), resource))
        }
        results
      }
    }
  }

  override suspend fun count(query: SearchQuery): Long {
    return db.useReaderConnection { transactor ->
      transactor.usePrepared(query.query) { statement ->
        bindArgs(statement, query.args)
        if (statement.step()) {
          statement.getLong(0)
        } else {
          0L
        }
      }
    }
  }

  override suspend fun searchForwardReferencedResources(
    query: SearchQuery,
  ): List<ForwardIncludeSearchResult> {
    return db.useReaderConnection { transactor ->
      transactor.usePrepared(query.query) { statement ->
        bindArgs(statement, query.args)
        val results = mutableListOf<ForwardIncludeSearchResult>()
        while (statement.step()) {
          val searchIndex = statement.getText(0)
          val baseResourceUUID = Uuid.parse(statement.getText(1))
          val json = statement.getText(2)
          val resource = deserializeResource(json)
          results.add(ForwardIncludeSearchResult(searchIndex, baseResourceUUID, resource))
        }
        results
      }
    }
  }

  override suspend fun searchReverseReferencedResources(
    query: SearchQuery,
  ): List<ReverseIncludeSearchResult> {
    return db.useReaderConnection { transactor ->
      transactor.usePrepared(query.query) { statement ->
        bindArgs(statement, query.args)
        val results = mutableListOf<ReverseIncludeSearchResult>()
        while (statement.step()) {
          val searchIndex = statement.getText(0)
          val baseResourceTypeWithId = statement.getText(1)
          val json = statement.getText(2)
          val resource = deserializeResource(json)
          results.add(ReverseIncludeSearchResult(searchIndex, baseResourceTypeWithId, resource))
        }
        results
      }
    }
  }

  override suspend fun getAllLocalChanges(): List<LocalChange> =
    localChangeDao.getAllLocalChanges().map { it.toLocalChange() }

  override suspend fun getAllChangesForEarliestChangedResource(): List<LocalChange> =
    localChangeDao.getAllChangesForEarliestChangedResource().map { it.toLocalChange() }

  override suspend fun getLocalChangesCount(): Int = localChangeDao.getLocalChangesCount()

  override suspend fun deleteUpdates(token: LocalChangeToken) {
    localChangeDao.discardLocalChanges(token)
  }

  override suspend fun deleteUpdates(resources: List<Resource>) {
    localChangeDao.discardLocalChanges(resources)
  }

  override suspend fun updateResourceAndReferences(
    currentResourceId: String,
    updatedResource: Resource,
  ) {
    withTransaction {
      val currentResourceEntity = selectEntity(updatedResource.resourceTypeEnum, currentResourceId)
      val oldResource = fhirJsonParser.decodeFromString(currentResourceEntity.serializedResource)
      val resourceUuid = currentResourceEntity.resourceUuid
      updateResourceEntity(resourceUuid, updatedResource)

      if (currentResourceId == updatedResource.id.orEmpty()) {
        return@withTransaction
      }

      // Update LocalChange records and identify referring resources. We update LocalChange records
      // first because they may contain references to the old resource ID that aren't present in the
      // latest ResourceEntity; the LocalChangeResourceReferenceEntity table lets us find them.
      val uuidsOfReferringResources =
        localChangeDao.updateResourceIdAndReferences(
          resourceUuid = resourceUuid,
          oldResource = oldResource,
          updatedResourceId = updatedResource.id.orEmpty(),
        )

      updateReferringResources(
        referringResourcesUuids = uuidsOfReferringResources,
        oldResource = oldResource,
        updatedResource = updatedResource,
      )
    }
  }

  /** Updates the [ResourceEntity] (resource + resourceId) associated with [resourceUuid]. */
  private suspend fun updateResourceEntity(resourceUuid: Uuid, updatedResource: Resource) =
    resourceDao.updateResourceWithUuid(resourceUuid, updatedResource)

  /**
   * Updates all [Resource]s (and their [ResourceEntity]s) that refer to the affected resource so
   * the references point at the new resource ID. The reference index is refreshed as part of
   * [ResourceDao.updateResourceWithUuid].
   */
  private suspend fun updateReferringResources(
    referringResourcesUuids: List<Uuid>,
    oldResource: Resource,
    updatedResource: Resource,
  ) {
    val oldReferenceValue = "${oldResource.resourceTypeEnum.name}/${oldResource.id.orEmpty()}"
    val updatedReferenceValue =
      "${updatedResource.resourceTypeEnum.name}/${updatedResource.id.orEmpty()}"
    referringResourcesUuids.forEach { resourceUuid ->
      resourceDao.getResourceEntity(resourceUuid)?.let {
        val referringResource = fhirJsonParser.decodeFromString(it.serializedResource)
        val updatedReferringResource =
          addUpdatedReferenceToResource(
            referringResource,
            oldReferenceValue,
            updatedReferenceValue,
          )
        resourceDao.updateResourceWithUuid(resourceUuid, updatedReferringResource)
      }
    }
  }

  override suspend fun getLocalChanges(type: ResourceType, id: String): List<LocalChange> =
    localChangeDao.getLocalChanges(type, id).map { it.toLocalChange() }

  override suspend fun getLocalChanges(resourceUuid: Uuid): List<LocalChange> =
    localChangeDao.getLocalChanges(resourceUuid).map { it.toLocalChange() }

  override suspend fun purge(type: ResourceType, ids: Set<String>, forcePurge: Boolean) {
    inTransaction {
      ids.forEach { id ->
        selectEntity(type, id)

        val localChanges = localChangeDao.getLocalChanges(type, id)
        if (localChanges.isNotEmpty() && !forcePurge) {
          throw IllegalStateException(
            "Resource with type $type and id $id has local changes, either sync with server or FORCE_PURGE required",
          )
        }

        resourceDao.deleteResource(id, type)
        if (localChanges.isNotEmpty()) {
          localChangeDao.discardLocalChanges(id, type)
        }
      }
    }
  }

  override suspend fun getLocalChangeResourceReferences(
    localChangeIds: List<Long>,
  ): List<LocalChangeResourceReference> {
    if (localChangeIds.isEmpty()) return emptyList()
    return localChangeDao.getReferencesForLocalChanges(localChangeIds).map {
      LocalChangeResourceReference(
        localChangeId = it.localChangeId,
        resourceReferenceValue = it.resourceReferenceValue,
        resourceReferencePath = it.resourceReferencePath,
      )
    }
  }

  private fun bindArgs(statement: SQLiteStatement, args: List<Any>) {
    args.forEachIndexed { i, arg ->
      when (arg) {
        is String -> statement.bindText(i + 1, arg)
        is Long -> statement.bindLong(i + 1, arg)
        is Double -> statement.bindDouble(i + 1, arg)
        is Int -> statement.bindLong(i + 1, arg.toLong())
        else -> statement.bindText(i + 1, arg.toString())
      }
    }
  }

  private suspend fun insertIndices(
    resourceUuid: Uuid,
    resourceType: ResourceType,
    indices: ResourceIndices,
  ) {
    indices.stringIndices.forEach {
      resourceDao.insertStringIndex(
        StringIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.referenceIndices.forEach {
      resourceDao.insertReferenceIndex(
        ReferenceIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.tokenIndices.forEach {
      resourceDao.insertCodeIndex(
        TokenIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.quantityIndices.forEach {
      resourceDao.insertQuantityIndex(
        QuantityIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.uriIndices.forEach {
      resourceDao.insertUriIndex(
        UriIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.dateIndices.forEach {
      resourceDao.insertDateIndex(
        DateIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.dateTimeIndices.forEach {
      resourceDao.insertDateTimeIndex(
        DateTimeIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.numberIndices.forEach {
      resourceDao.insertNumberIndex(
        NumberIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
    indices.positionIndices.forEach {
      resourceDao.insertPositionIndex(
        PositionIndexEntity(
          id = 0,
          resourceUuid = resourceUuid,
          resourceType = resourceType,
          index = it,
        ),
      )
    }
  }
}
