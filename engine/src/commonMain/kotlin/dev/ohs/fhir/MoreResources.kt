/*
 * Copyright 2022-2026 Google LLC
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

package dev.ohs.fhir

import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Returns the FHIR resource type.
 *
 * @throws IllegalArgumentException if class name cannot be mapped to valid resource type
 */
fun <R : Resource> getResourceType(kClass: KClass<R>): ResourceType {
  val name =
    kClass.simpleName ?: throw IllegalArgumentException("Cannot resolve resource type for $kClass")
  return try {
    ResourceType.valueOf(name)
  } catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("Cannot resolve resource type for $kClass", e)
  }
}

/** Returns the [KClass] object for the resource type. */
fun <R : Resource> getResourceClass(resourceType: ResourceType): KClass<R> =
  getResourceClass(resourceType.name)

/**
 * Returns the [KClass] object for the resource type.
 *
 * Uses the codegen-produced `getResourceClassByName` lookup table — works uniformly across all
 * targets (no JVM reflection involved).
 *
 * @throws IllegalArgumentException if the name does not match any FHIR R4 resource type.
 */
@Suppress("UNCHECKED_CAST")
fun <R : Resource> getResourceClass(resourceTypeName: String): KClass<R> {
  // Strip any curly-brace namespace prefix (e.g. "{http://hl7.org/fhir}Patient" -> "Patient")
  // mirroring the engine's CQL-engine workaround.
  val className = resourceTypeName.replace(Regex("\\{[^}]*\\}"), "")
  return (dev.ohs.fhir.index.getResourceClassByName(className) as? KClass<R>)
    ?: throw IllegalArgumentException("Unknown FHIR R4 resource type: $resourceTypeName")
}

internal val Resource.resourceType: String
  get() = this::class.simpleName ?: error("Cannot determine resource type for $this")

internal val Resource.resourceTypeEnum: ResourceType
  get() = ResourceType.valueOf(resourceType)

internal val Resource.versionId: String?
  get() = meta?.versionId?.value

internal val Resource.lastUpdated: Instant?
  get() {
    val dt = (meta?.lastUpdated?.value as? FhirDateTime.DateTime) ?: return null
    return dt.dateTime.toInstant(dt.utcOffset)
  }

/**
 * Returns a copy of this [Resource] with `meta.versionId` and/or `meta.lastUpdated` overridden. If
 * both arguments are null, the receiver is returned unchanged.
 *
 * Round-trips through FHIR JSON because kotlin-fhir's [Resource] is immutable and its abstract
 * `Resource.Builder` doesn't expose `meta` polymorphically.
 */
internal fun Resource.updateMeta(versionId: String?, lastUpdated: Instant?): Resource {
  if (versionId == null && lastUpdated == null) return this
  val parser = FhirR4Json()
  val obj = Json.parseToJsonElement(parser.encodeToString(this)).jsonObject.toMutableMap()
  val meta = (obj["meta"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
  versionId?.let { meta["versionId"] = JsonPrimitive(it) }
  lastUpdated?.let { meta["lastUpdated"] = JsonPrimitive(it.toString()) }
  obj["meta"] = JsonObject(meta)
  return parser.decodeFromString(JsonObject(obj).toString())
}

/**
 * Returns a copy of this [Resource] with `id` overridden. Round-trips through FHIR JSON because
 * kotlin-fhir's [Resource] is immutable and its abstract `Resource.Builder` doesn't expose `id`
 * polymorphically.
 */
internal fun Resource.withId(newId: String): Resource {
  val parser = FhirR4Json()
  val obj = Json.parseToJsonElement(parser.encodeToString(this)).jsonObject.toMutableMap()
  obj["id"] = JsonPrimitive(newId)
  return parser.decodeFromString(JsonObject(obj).toString())
}
