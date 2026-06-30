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
package dev.ohs.fhir.sync

/**
 * Provides an authorization method for the HTTP requests FHIR Engine sends to the FHIR server.
 *
 * FHIR Engine does not handle user authentication. The application should handle user
 * authentication and provide the appropriate authentication method so the HTTP requests FHIR Engine
 * sends to the FHIR server contain the correct user information for the request to be
 * authenticated.
 *
 * The implementation can provide different `HttpAuthenticationMethod`s at runtime. This is
 * important if the authentication token expires or the user needs to re-authenticate.
 */
interface HttpAuthenticator
