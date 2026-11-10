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
package dev.ohs.fhir.engine.benchmark

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsString
import kotlin.js.toJsString
import org.w3c.xhr.XMLHttpRequest

internal actual suspend fun httpGet(path: String): String? = request("GET", path, null)

internal actual suspend fun httpPost(path: String, body: String): Boolean =
  request("POST", path, body) != null

private suspend fun request(method: String, path: String, body: String?): String? =
  suspendCoroutine { continuation ->
    val xhr = XMLHttpRequest()
    xhr.open(method, path)
    xhr.onload = {
      continuation.resume(if (xhr.status.toInt() in 200..299) xhr.responseText else null)
    }
    xhr.onerror = { continuation.resume(null) }
    if (body == null) xhr.send() else jsSend(xhr, body.toJsString())
  }

/** Wasm's `send` overloads cover Document, Blob, FormData and URLSearchParams, but not a string. */
private fun jsSend(request: XMLHttpRequest, body: JsString): Unit = js("request.send(body)")
