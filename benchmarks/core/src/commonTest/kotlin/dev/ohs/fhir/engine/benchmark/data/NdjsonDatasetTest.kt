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
package dev.ohs.fhir.engine.benchmark.data

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

private fun patient(id: String) = """{"resourceType":"Patient","id":"$id"}"""

private fun organization(id: String) = """{"resourceType":"Organization","id":"$id"}"""

private fun observation(id: String, code: String) =
  """{"resourceType":"Observation","id":"$id","status":"final",""" +
    """"code":{"coding":[{"code":"$code"}]}}"""

/** Fake platform reader: one flow per file. */
private class FakeLines(private val files: Map<String, List<String>>) {
  val requested = mutableListOf<String>()

  operator fun invoke(name: String): Flow<String> {
    requested += name
    return files[name].orEmpty().asFlow()
  }
}

class NdjsonDatasetTest {

  @Test
  fun `orders referenced types before the resources that reference them`() = runTest {
    val lines =
      FakeLines(
        mapOf(
          "Patient.ndjson" to listOf(patient("p1")),
          "Organization.ndjson" to listOf(organization("o1")),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson", "Organization.ndjson"),
        seed = 1,
        requestedPopulation = 1,
        lines = lines::invoke,
      )

    assertEquals(listOf("o1", "p1"), dataset.resources().toList().map { it.id })
    assertEquals(listOf("p1"), dataset.patientIds)
    assertEquals(1, dataset.population)
  }

  @Test
  fun `keeps the valid lines when one line fails to parse`() = runTest {
    val lines =
      FakeLines(
        mapOf("Patient.ndjson" to listOf(patient("p1"), "{not json", "", patient("p2"))),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson"),
        seed = 1,
        requestedPopulation = 2,
        lines = lines::invoke,
      )

    assertEquals(listOf("p1", "p2"), dataset.patientIds)
    assertEquals(1, dataset.parseFailures.size)
    assertContains(dataset.parseFailures.single(), "Patient.ndjson")
  }

  @Test
  fun `discovers the most common observation code`() = runTest {
    val lines =
      FakeLines(
        mapOf(
          "Patient.ndjson" to listOf(patient("p1")),
          "Observation.ndjson" to
            listOf(
              observation("o1", "1234-5"),
              observation("o2", "1234-5"),
              observation("o3", "9"),
            ),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson", "Observation.ndjson"),
        seed = 1,
        requestedPopulation = 1,
        lines = lines::invoke,
      )

    assertEquals("1234-5", dataset.sampleObservationCode)
  }

  @Test
  fun `never opens a file whose type no workload queries`() = runTest {
    val lines = FakeLines(mapOf("Patient.ndjson" to listOf(patient("p1"))))

    NdjsonDataset.load(
      fileNames = listOf("Patient.ndjson", "Claim.ndjson"),
      seed = 1,
      requestedPopulation = 1,
      lines = lines::invoke,
    )

    assertEquals(listOf("Patient.ndjson"), lines.requested)
  }

  @Test
  fun `is empty when no files are named`() = runTest {
    val lines = FakeLines(emptyMap())

    val dataset =
      NdjsonDataset.load(
        fileNames = emptyList(),
        seed = 1,
        requestedPopulation = 0,
        lines = lines::invoke,
      )

    assertEquals(0, dataset.population)
    assertTrue(dataset.patientIds.isEmpty())
    assertTrue(dataset.resources().toList().isEmpty())
    assertEquals(0, dataset.resourceCount)
  }

  @Test
  fun `adds up a type split across several files`() = runTest {
    // Packaging splits large types across numbered files.
    val lines =
      FakeLines(
        mapOf(
          "Patient.00.ndjson" to listOf(patient("p1"), patient("p2")),
          "Patient.01.ndjson" to listOf(patient("p3")),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.00.ndjson", "Patient.01.ndjson"),
        seed = 1,
        requestedPopulation = 3,
        lines = lines::invoke,
      )

    assertEquals(3, dataset.resourceCount)
    assertEquals(mapOf("Patient" to 3), dataset.manifest().resourceCounts)
    assertEquals(listOf("p1", "p2", "p3"), dataset.patientIds)
  }

  @Test
  fun `re-reads the corpus instead of holding it`() = runTest {
    // The parsed corpus does not fit in a phone's heap; only counts and ids are held.
    val lines = FakeLines(mapOf("Patient.ndjson" to listOf(patient("p1"), patient("p2"))))
    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson"),
        seed = 1,
        requestedPopulation = 2,
        lines = lines::invoke,
      )
    val afterLoad = lines.requested.size

    dataset.resources().toList()
    dataset.resources().toList()

    assertEquals(afterLoad + 2, lines.requested.size)
  }

  @Test
  fun `counts every resource without collecting them`() = runTest {
    val lines =
      FakeLines(
        mapOf(
          "Patient.ndjson" to listOf(patient("p1"), patient("p2")),
          "Organization.ndjson" to listOf(organization("o1")),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson", "Organization.ndjson"),
        seed = 1,
        requestedPopulation = 2,
        lines = lines::invoke,
      )

    assertEquals(3, dataset.resourceCount)
  }

  @Test
  fun `keeps the manifest counts across a re-read`() = runTest {
    val lines =
      FakeLines(
        mapOf(
          "Patient.ndjson" to listOf(patient("p1")),
          "Organization.ndjson" to listOf(organization("o1")),
        ),
      )

    val dataset =
      NdjsonDataset.load(
        fileNames = listOf("Patient.ndjson", "Organization.ndjson"),
        seed = 1,
        requestedPopulation = 1,
        lines = lines::invoke,
      )
    val before = dataset.manifest()
    dataset.resources().toList()

    assertEquals(before, dataset.manifest())
    assertEquals(mapOf("Patient" to 1, "Organization" to 1), before.resourceCounts)
  }
}

/** Stands in for the platform's cache file. */
private class FakeCache(private val entries: MutableMap<String, String> = mutableMapOf()) :
  DatasetCache {
  var reads = 0
  var writes = 0

  override suspend fun read(key: String): String? {
    reads++
    return entries[key]
  }

  override suspend fun write(key: String, contents: String) {
    writes++
    entries[key] = contents
  }

  fun poison(key: String) {
    entries[key] = "{not json"
  }
}

/** Macrobenchmark restarts the process every iteration, so the corpus scan is cached. */
class NdjsonDatasetCacheTest {

  private val corpus =
    mapOf(
      "Organization.ndjson" to listOf(organization("o1")),
      "Patient.ndjson" to listOf(patient("p1"), patient("p2")),
    )

  private suspend fun load(lines: FakeLines, cache: DatasetCache?) =
    NdjsonDataset.load(
      fileNames = listOf("Organization.ndjson", "Patient.ndjson"),
      seed = 1,
      requestedPopulation = 2,
      lines = lines::invoke,
      cache = cache,
      cacheKey = KEY,
    )

  @Test
  fun `a cold load stores what it learned`() = runTest {
    val cache = FakeCache()

    load(FakeLines(corpus), cache)

    assertEquals(1, cache.writes)
  }

  @Test
  fun `a warm load opens no data file at all`() = runTest {
    val cache = FakeCache()
    load(FakeLines(corpus), cache)

    val lines = FakeLines(corpus)
    load(lines, cache)

    assertEquals(emptyList(), lines.requested)
  }

  @Test
  fun `a warm load describes the corpus exactly as the cold one did`() = runTest {
    val cache = FakeCache()
    val cold = load(FakeLines(corpus), cache)

    val warm = load(FakeLines(corpus), cache)

    assertEquals(cold.patientIds, warm.patientIds)
    assertEquals(cold.population, warm.population)
    assertEquals(cold.resourceCount, warm.resourceCount)
    assertEquals(cold.manifest(), warm.manifest())
    assertEquals(cold.sampleOrganizationId, warm.sampleOrganizationId)
  }

  @Test
  fun `a warm load still streams resources from the files`() = runTest {
    val cache = FakeCache()
    load(FakeLines(corpus), cache)
    val lines = FakeLines(corpus)
    val warm = load(lines, cache)

    val ids = warm.resources().toList().map { it.id }

    assertEquals(listOf("o1", "p1", "p2"), ids)
  }

  @Test
  fun `an unreadable cache entry falls back to scanning`() = runTest {
    val cache = FakeCache()
    cache.poison(KEY)

    val dataset = load(FakeLines(corpus), cache)

    assertEquals(listOf("p1", "p2"), dataset.patientIds)
  }

  private companion object {
    const val KEY = "corpus-abc123"
  }
}
