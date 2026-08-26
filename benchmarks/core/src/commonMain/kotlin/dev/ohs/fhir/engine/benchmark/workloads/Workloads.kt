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
package dev.ohs.fhir.engine.benchmark.workloads

import dev.ohs.fhir.engine.benchmark.Workload

/** The single catalogue. Both the in-process runner and Android read from here. */
object Workloads {

  fun all(): List<Workload> =
    CrudWorkloads.all() + SearchWorkloads.all() + SyncWorkloads.all() + ServerWorkloads.all()

  fun byGroup(group: String): List<Workload> = all().filter { it.group == group }

  fun byGroups(groups: Collection<String>): List<Workload> = all().filter { it.group in groups }

  fun byId(id: String): Workload =
    all().firstOrNull { it.id == id } ?: error("Unknown benchmark workload id: $id")
}
