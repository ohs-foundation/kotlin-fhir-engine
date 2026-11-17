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

import com.sun.net.httpserver.HttpServer
import dev.ohs.fhir.engine.CacheConfiguration
import dev.ohs.fhir.engine.NetworkConfiguration
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HttpCacheTest {
  @Test
  fun httpCache_servesARepeatedRequestFromCacheDir() = runTest {
    val requests = AtomicInteger()
    val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/Patient/") { exchange ->
          requests.incrementAndGet()
          val id = exchange.requestURI.path.substringAfterLast("/")
          val body = """{"resourceType":"Patient","id":"$id"}""".toByteArray()
          exchange.responseHeaders.add("Content-Type", "application/fhir+json")
          // p2 is marked private, as a server behind a login may do.
          val scope = if (id == "p2") "private, " else ""
          exchange.responseHeaders.add("Cache-Control", "${scope}max-age=60")
          exchange.sendResponseHeaders(200, body.size.toLong())
          exchange.responseBody.use { it.write(body) }
        }
        start()
      }
    val cacheDir = Files.createTempDirectory("http-cache").toFile()
    try {
      val service =
        KtorHttpService.Builder(
            "http://127.0.0.1:${server.address.port}/",
            NetworkConfiguration(httpCache = CacheConfiguration(cacheDir.path, 1024L * 1024L)),
          )
          .build()

      service.get("Patient/p1", emptyMap())
      service.get("Patient/p1", emptyMap())
      service.get("Patient/p2", emptyMap())
      service.get("Patient/p2", emptyMap())

      assertEquals(2, requests.get())
      assertEquals(2, cacheDir.walk().count { it.isFile })
    } finally {
      server.stop(0)
      cacheDir.deleteRecursively()
    }
  }
}
