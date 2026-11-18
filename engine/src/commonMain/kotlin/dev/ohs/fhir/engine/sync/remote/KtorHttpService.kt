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
package dev.ohs.fhir.engine.sync.remote

import co.touchlab.kermit.Logger as KermitLogger
import dev.ohs.fhir.engine.NetworkConfiguration
import dev.ohs.fhir.engine.sync.HttpAuthenticator
import dev.ohs.fhir.model.r4.Resource
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.compression.ContentEncodingConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Ktor implementation of the [FhirHttpService]. */
internal class KtorHttpService(
  private val client: HttpClient,
  private val fhirJson: Json = Json,
  compressUploads: Boolean = false,
) : FhirHttpService {

  private val compressUploads: Boolean

  init {
    if (compressUploads && !supportsRequestCompression()) {
      KermitLogger.w {
        "uploadWithGzip is not supported on this platform. Uploads are sent uncompressed."
      }
    }
    this.compressUploads = compressUploads && supportsRequestCompression()
  }

  /** Closes the [HttpClient]. Every later request on this service fails. */
  override fun close() {
    client.close()
  }

  /**
   * Parses a response, and only when that fails retries with [repair]. Sanitizing cuts every
   * `...Date` field to a date, dateTime fields included, so valid data must skip it.
   */
  private fun decode(json: String): Resource =
    try {
      fhirJson.decodeFromString<Resource>(json)
    } catch (_: Exception) {
      fhirJson.decodeFromString<Resource>(repair(json))
    }

  /**
   * Sanitizes only the bundle entries that fail to parse, so one invalid resource leaves the rest
   * of the bundle as the server sent it. Any other response is sanitized whole.
   */
  private fun repair(json: String): String {
    val bundle =
      (runCatching { lenientJson.parseToJsonElement(json) }.getOrNull() as? JsonObject)?.takeIf {
        (it["resourceType"] as? JsonPrimitive)?.content == "Bundle"
      }
    val entries = bundle?.get("entry") as? JsonArray ?: return sanitizeJson(json)
    val repaired =
      entries.map { entry ->
        val resource = (entry as? JsonObject)?.get("resource") ?: return@map entry
        val resourceJson = lenientJson.encodeToString(JsonElement.serializer(), resource)
        if (runCatching { fhirJson.decodeFromString<Resource>(resourceJson) }.isSuccess) {
          entry
        } else {
          JsonObject(
            entry + ("resource" to lenientJson.parseToJsonElement(sanitizeJson(resourceJson))),
          )
        }
      }
    return lenientJson.encodeToString(
      JsonElement.serializer(),
      JsonObject(bundle + ("entry" to JsonArray(repaired))),
    )
  }

  /**
   * Repairs invalid server JSON that kotlin-fhir rejects:
   * 1. Truncates a dateTime in a field ending in `Date`, such as a birthDate with a time.
   * 2. Strips narratives, which fail to parse when their status or div is missing.
   */
  private fun sanitizeJson(json: String): String {
    val sanitized =
      json.replace(dateFieldWithTimeRegex) { match ->
        "\"${match.groupValues[1]}\" : \"${match.groupValues[2]}\""
      }
    return try {
      val element = lenientJson.parseToJsonElement(sanitized)
      lenientJson.encodeToString(JsonElement.serializer(), stripNarrativeText(element))
    } catch (_: Exception) {
      sanitized
    }
  }

  private fun stripNarrativeText(element: JsonElement): JsonElement =
    when (element) {
      is JsonObject ->
        JsonObject(
          element.jsonObject
            .filterKeys { it != "text" || !looksLikeNarrative(element[it]) }
            .mapValues { (_, v) -> stripNarrativeText(v) },
        )
      is JsonArray ->
        JsonArray(
          element.jsonArray.map { stripNarrativeText(it) },
        )
      else -> element
    }

  private fun looksLikeNarrative(element: JsonElement?): Boolean =
    element is JsonObject && element.containsKey("div")

  override suspend fun get(path: String, headers: Map<String, String>): Resource {
    val json: String =
      client.get(path) { headers { headers.forEach { (k, v) -> append(k, v) } } }.body()
    return decode(json)
  }

  override suspend fun post(
    path: String,
    resource: Resource,
    headers: Map<String, String>,
  ): Resource {
    val body = uploadBody(ContentType.Application.Json) { fhirJson.encodeToString(resource) }
    val json: String =
      client
        .post(path) {
          headers { headers.forEach { (k, v) -> append(k, v) } }
          setUploadBody(body)
        }
        .body()
    return decode(json)
  }

  override suspend fun put(
    path: String,
    resource: Resource,
    headers: Map<String, String>,
  ): Resource {
    val body = uploadBody(ContentType.Application.Json) { fhirJson.encodeToString(resource) }
    val json: String =
      client
        .put(path) {
          headers { headers.forEach { (k, v) -> append(k, v) } }
          setUploadBody(body)
        }
        .body()
    return decode(json)
  }

  override suspend fun patch(
    path: String,
    patchDocument: JsonArray,
    headers: Map<String, String>,
  ): Resource {
    val body =
      uploadBody(ContentType.parse("application/json-patch+json")) { patchDocument.toString() }
    val json: String =
      client
        .patch(path) {
          headers { headers.forEach { (k, v) -> append(k, v) } }
          setUploadBody(body)
        }
        .body()
    return decode(json)
  }

  /**
   * Encodes the upload body, gzipped when [compressUploads] is on, off the caller's thread so a
   * large bundle doesn't block it. android-fhir does both on OkHttp's threads. The gzipped bytes
   * are sent whole, so the request carries a Content-Length, as android-fhir's
   * GzipUploadInterceptor does. Servers that reject chunked uploads need it.
   */
  private suspend fun uploadBody(contentType: ContentType, encode: () -> String): OutgoingContent =
    withContext(Dispatchers.Default) {
      val body = encode()
      if (compressUploads) {
        ByteArrayContent(gzip(body.encodeToByteArray()), contentType)
      } else {
        TextContent(body, contentType)
      }
    }

  private fun HttpRequestBuilder.setUploadBody(body: OutgoingContent) {
    if (compressUploads) header(HttpHeaders.ContentEncoding, "gzip")
    setBody(body)
  }

  override suspend fun delete(path: String, headers: Map<String, String>): Resource {
    val json: String =
      client.delete(path) { headers { headers.forEach { (k, v) -> append(k, v) } } }.body()
    return decode(json)
  }

  companion object {
    private val lenientJson = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /**
     * Matches FHIR date-only fields (any field name ending in "Date") that incorrectly contain
     * DateTime values.
     */
    private val dateFieldWithTimeRegex = Regex(""""(\w*Date)"\s*:\s*"(\d{4}-\d{2}-\d{2})T[^"]*"""")

    fun builder(baseUrl: String, networkConfiguration: NetworkConfiguration) =
      Builder(baseUrl, networkConfiguration)
  }

  class Builder(
    private val baseUrl: String,
    private val networkConfiguration: NetworkConfiguration,
  ) {
    private var authenticator: HttpAuthenticator? = null
    private var httpLogger: HttpLogger? = null

    private fun HttpClientConfig<*>.configureClient() {
      expectSuccess = true
      install(HttpTimeout) {
        connectTimeoutMillis = networkConfiguration.connectionTimeOut * 1000
        requestTimeoutMillis = networkConfiguration.readTimeOut * 1000
        socketTimeoutMillis = networkConfiguration.writeTimeOut * 1000
      }

      // Uploads are gzipped in setUploadBody. The plugin only decompresses gzipped responses.
      if (networkConfiguration.uploadWithGzip && supportsRequestCompression()) {
        install(ContentEncoding) {
          gzip()
          mode = ContentEncodingConfig.Mode.DecompressResponse
        }
      }

      if (networkConfiguration.httpCache != null) {
        install(HttpCache)
      }

      install(DefaultRequest) {
        url(baseUrl)
        authenticator?.let {
          headers {
            val authMethod = it.getAuthenticationMethod()
            append(HttpHeaders.Authorization, authMethod.getAuthorizationHeader())
          }
        }
      }

      httpLogger?.let { loggerConfig ->
        install(Logging) {
          level =
            when (loggerConfig.level) {
              HttpLogger.Level.NONE -> LogLevel.NONE
              HttpLogger.Level.BASIC -> LogLevel.INFO
              HttpLogger.Level.HEADERS -> LogLevel.HEADERS
              HttpLogger.Level.BODY -> LogLevel.ALL
            }
          logger =
            object : io.ktor.client.plugins.logging.Logger {
              override fun log(message: String) {
                KermitLogger.v { message }
              }
            }
          sanitizeHeader { header -> loggerConfig.headersToIgnore.contains(header) }
        }
      }
    }

    fun setAuthenticator(authenticator: HttpAuthenticator?) = apply {
      this.authenticator = authenticator
    }

    fun setHttpLogger(httpLogger: HttpLogger) = apply { this.httpLogger = httpLogger }

    internal fun build(engine: HttpClientEngine): KtorHttpService {
      val client = HttpClient(engine) { configureClient() }
      return KtorHttpService(client, compressUploads = networkConfiguration.uploadWithGzip)
    }

    fun build(): KtorHttpService {
      val client = HttpClient { configureClient() }
      return KtorHttpService(client, compressUploads = networkConfiguration.uploadWithGzip)
    }
  }
}
