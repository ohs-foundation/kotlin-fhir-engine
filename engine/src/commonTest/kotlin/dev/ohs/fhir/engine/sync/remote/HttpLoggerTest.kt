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

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import dev.ohs.fhir.engine.NetworkConfiguration
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.model.r4.Patient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpLoggerTest {
  @Test
  fun bodyLevel_logsEveryHeaderWhenNoneAreIgnored() = runTest {
    val messages = intercept(HttpLogger(HttpLogger.Level.BODY))

    for (line in
      listOf(
        "Restricted: request-restricted-value",
        "Unrestricted: request-unrestricted-value",
        "Restricted: response-restricted-value",
        "Unrestricted: response-unrestricted-value",
      )) {
      assertTrue(messages.any { it.contains(line) }, "Missing log line $line")
    }
  }

  @Test
  fun bodyLevel_hidesIgnoredHeaders() = runTest {
    val messages = intercept(HttpLogger(HttpLogger.Level.BODY, setOf("Restricted")))

    assertTrue(messages.any { it.contains("Unrestricted: request-unrestricted-value") })
    assertTrue(messages.any { it.contains("Unrestricted: response-unrestricted-value") })
    assertFalse(messages.any { it.contains("request-restricted-value") })
    assertFalse(messages.any { it.contains("response-restricted-value") })
  }

  /** Sends one request with two headers, answers with two headers, and returns what was logged. */
  private suspend fun intercept(httpLogger: HttpLogger): List<String> {
    val messages = mutableListOf<String>()
    val writer =
      object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
          messages.add(message)
        }
      }
    Logger.addLogWriter(writer)
    try {
      KtorHttpService.Builder("/", NetworkConfiguration())
        .setHttpLogger(httpLogger)
        .build(
          engine =
            MockEngine {
              respond(
                fhirJsonParser.encodeToString(Patient(id = "patient-001")),
                headers =
                  headersOf(
                    "Restricted" to listOf("response-restricted-value"),
                    "Unrestricted" to listOf("response-unrestricted-value"),
                  ),
              )
            },
        )
        .get(
          "Patient/patient-001",
          mapOf(
            "Restricted" to "request-restricted-value",
            "Unrestricted" to "request-unrestricted-value",
          ),
        )
    } finally {
      Logger.setLogWriters(Logger.config.logWriterList - writer)
    }
    return messages
  }
}
