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
package dev.ohs.fhir.engine.search

import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.db.ResourceWithUUID
import dev.ohs.fhir.model.r4.Resource

/**
 * One part of an unfiltered sorted search, read in an index's own order.
 *
 * The single query joins every resource to its values, groups and sorts them all, and only then
 * applies `LIMIT`, so a first page costs a sort of the whole type. Read in index order the limit
 * stops the read early instead.
 *
 * A search's segments run in turn: resources with a value in its first sort table, then, for dates,
 * those with a value in the second alone, then those with none. A resource with several values is
 * read once, at its smallest when ascending and its largest when descending, as the single query
 * orders it.
 */
internal class SortSegment(private val select: String, private val args: List<Any>) {
  /** At most [limit] rows from [offset], or every row from [offset] when [limit] is null. */
  fun page(limit: Int?, offset: Int) =
    SearchQuery("$select\nLIMIT ? OFFSET ?", args + (limit ?: -1) + offset)

  /** The number of rows in this segment. */
  val count: SearchQuery
    get() = SearchQuery("SELECT COUNT(*) FROM (\n$select\n)", args)
}

/**
 * This search's segments, or null when it is unsorted or filtered. A filter usually leaves few
 * rows, which sort cheaply, whereas walking the index would pass every value that does not match.
 */
internal fun Search.getSortSegments(): List<SortSegment>? {
  val sort = sort ?: return null
  if (getFilterQueries().isNotEmpty() || nestedSearches.isNotEmpty()) return null
  val tables = sort.sortTables()
  val walks =
    tables.mapIndexed { i, table ->
      walk(table, sort.paramName, descending = order == Order.DESCENDING, earlier = tables.take(i))
    }
  return walks + withNoValue(tables, sort.paramName)
}

/**
 * Reads [segments] in turn as one list, applying [Search.count] and [Search.from] across them. As
 * in the single query, `from` applies only with a `count`. A segment is counted only when a page
 * starts past its end, to find where in the next one the page starts.
 */
internal suspend fun <R : Resource> Search.readInOrder(
  database: Database,
  segments: List<SortSegment>,
): List<ResourceWithUUID<R>> {
  val results = mutableListOf<ResourceWithUUID<R>>()
  var offset = if (count != null) from ?: 0 else 0
  for (segment in segments) {
    val limit = count?.let { it - results.size }
    if (limit == 0) break
    val rows = database.search<R>(segment.page(limit, offset))
    results += rows
    offset =
      if (rows.isEmpty() && offset > 0) {
        (offset - database.count(segment.count)).toInt().coerceAtLeast(0)
      } else {
        0
      }
  }
  return results
}

/**
 * Walks [table]'s sort index in order. Only the row holding a resource's first value in that order
 * passes, ties going to the lowest row id, so each resource is read once. Resources with a value in
 * an [earlier] table were read there and are skipped.
 */
private fun Search.walk(
  table: SortTableInfo,
  paramName: String,
  descending: Boolean,
  earlier: List<SortTableInfo>,
): SortSegment {
  val column = table.columnName
  val before = if (descending) ">" else "<"
  val lines =
    listOf(
      "SELECT a.resourceUuid, a.serializedResource",
      "FROM ${table.tableName} b",
      "JOIN ResourceEntity a ON a.resourceUuid = b.resourceUuid",
      "WHERE b.resourceType = ? AND b.index_name = ?",
      "AND NOT EXISTS (",
      "SELECT 1 FROM ${table.tableName} m",
      "WHERE m.resourceUuid = b.resourceUuid AND m.index_name = b.index_name",
      "AND (m.$column $before b.$column OR (m.$column = b.$column AND m.id < b.id))",
      ")",
    ) +
      earlier.map { it.absentFor("b") } +
      "ORDER BY b.$column ${if (descending) "DESC" else "ASC"}"
  return SortSegment(
    lines.joinToString("\n"),
    listOf(type.name, paramName) + earlier.map { paramName },
  )
}

/** Resources with no value in any of [tables], by id so that pages are stable. */
private fun Search.withNoValue(tables: List<SortTableInfo>, paramName: String): SortSegment {
  val lines =
    listOf(
      "SELECT a.resourceUuid, a.serializedResource",
      "FROM ResourceEntity a",
      "WHERE a.resourceType = ?",
    ) + tables.map { it.absentFor("a") } + "ORDER BY a.resourceId"
  return SortSegment(lines.joinToString("\n"), listOf(type.name) + tables.map { paramName })
}

/** A condition that the resource [alias] refers to has no value for the bound parameter here. */
private fun SortTableInfo.absentFor(alias: String) =
  "AND NOT EXISTS (SELECT 1 FROM $tableName e WHERE e.resourceUuid = $alias.resourceUuid AND e.index_name = ?)"
