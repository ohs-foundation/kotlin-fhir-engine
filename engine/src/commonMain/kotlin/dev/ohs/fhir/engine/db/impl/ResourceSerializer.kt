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

import dev.ohs.fhir.engine.ResourceStorageFormat
import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Singleton FHIR JSON parser for serializing/deserializing resources. Replaces HAPI's
 * `FhirContext.forR4Cached().newJsonParser()`. Thread-safe and reusable.
 */
internal val fhirJsonParser = Json {
  explicitNulls = false
  encodeDefaults = false
}

/**
 * Binary counterpart of [fhirJsonParser]. kotlin-fhir's generated serializers drive the generic
 * `Encoder`/`Decoder` API rather than the JSON tree, so the same model classes encode to Protobuf
 * without a second set of type definitions.
 */
@OptIn(ExperimentalSerializationApi::class) internal val fhirProtoParser = ProtoBuf {}

private const val JSON_TAG: Byte = 0x4A // 'J'

private const val PROTOBUF_TAG: Byte = 0x50 // 'P'

/**
 * Encodes and decodes the `ResourceEntity.serializedResource` blob.
 *
 * Every blob carries a one-byte format tag, so the write format can change (by configuration, or
 * later by migration) without invalidating rows already on disk: a database may hold a mix of JSON
 * and Protobuf rows and still read back correctly.
 */
internal class ResourceSerializer(private val writeFormat: ResourceStorageFormat) {

  @OptIn(ExperimentalSerializationApi::class)
  fun encode(resource: Resource): ByteArray =
    when (writeFormat) {
      ResourceStorageFormat.JSON ->
        byteArrayOf(JSON_TAG) + fhirJsonParser.encodeToString(resource).encodeToByteArray()
      ResourceStorageFormat.PROTOBUF ->
        byteArrayOf(PROTOBUF_TAG) +
          fhirProtoParser.encodeToByteArray(Resource.serializer(), resource)
    }

  @OptIn(ExperimentalSerializationApi::class)
  fun decode(bytes: ByteArray): Resource =
    when (val tag = bytes[0]) {
      JSON_TAG -> fhirJsonParser.decodeFromString(bytes.decodeToString(startIndex = 1))
      PROTOBUF_TAG ->
        fhirProtoParser.decodeFromByteArray(Resource.serializer(), bytes.copyOfRange(1, bytes.size))
      else -> error("Unknown resource storage format tag: $tag")
    }

  /** The stored resource as FHIR JSON, for the local-change diff and the upload payload. */
  fun decodeToJson(bytes: ByteArray): String =
    if (bytes[0] == JSON_TAG) {
      bytes.decodeToString(startIndex = 1)
    } else {
      fhirJsonParser.encodeToString(decode(bytes))
    }
}
