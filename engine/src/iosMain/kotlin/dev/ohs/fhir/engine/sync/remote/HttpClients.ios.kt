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

import dev.ohs.fhir.engine.CacheConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLCache

/** The URL loading system's cache, on disk up to maxSize in cacheDir. */
internal actual fun httpClient(
  cache: CacheConfiguration?,
  configure: HttpClientConfig<*>.() -> Unit,
): HttpClient =
  HttpClient(Darwin) {
    cache?.let {
      engine {
        configureSession {
          setURLCache(
            NSURLCache(
              memoryCapacity = 0u,
              diskCapacity = it.maxSize.toULong(),
              diskPath = it.cacheDir,
            ),
          )
        }
      }
    }
    configure()
  }
