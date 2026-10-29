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

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.ohs.fhir.engine.ResourceStorageFormat
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Integer as FhirInteger
import dev.ohs.fhir.model.r4.Narrative
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.Xhtml
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest

/**
 * Compares the on-disk size of [ResourceStorageFormat.PROTOBUF] against
 * [ResourceStorageFormat.JSON] by loading one corpus into two otherwise identical databases.
 *
 * A tool, not a test: it runs only when `benchmarkResources` is set.
 *
 * ```
 * ./gradlew :engine:desktopTest --tests '*StorageFormatSizeBenchmark' \
 *   -PbenchmarkResources=10000 --rerun-tasks -i
 * ```
 *
 * Payload totals repeat exactly; the file can differ by one 4 KB page. Resources are inserted as a
 * sync would, with nothing pending upload, which is the case the ratio is quoted for.
 */
class StorageFormatSizeBenchmark {

  private val resourceCount = System.getProperty("benchmarkResources")?.toInt()

  @Test
  fun compareStorageFormats() =
    runTest(timeout = Duration.INFINITE) {
      val count = resourceCount ?: return@runTest
      val corpus = corpus(count)

      val measurements = ResourceStorageFormat.entries.map { load(it, corpus) }
      val json = measurements.first { it.format == ResourceStorageFormat.JSON }
      val protobuf = measurements.first { it.format == ResourceStorageFormat.PROTOBUF }

      println("resources=${corpus.size}  seed=$SEED")
      println()
      println("format    db_file_bytes  payload_bytes  bytes_per_resource  index_rows")
      measurements.forEach {
        println(
          "%-9s %14d %14d %19d %11d"
            .format(
              it.format,
              it.fileBytes,
              it.payloadBytes,
              it.payloadBytes / corpus.size,
              it.indexRows,
            ),
        )
      }
      println()
      println(
        "db file   ${change(protobuf.fileBytes, json.fileBytes)}   " +
          "(index tables identical in both)",
      )
      println(
        "payload   ${change(protobuf.payloadBytes, json.payloadBytes)}   (exact, byte for byte)",
      )
    }

  private suspend fun load(format: ResourceStorageFormat, corpus: List<Resource>): Measurement {
    val storageDirectory = Files.createTempDirectory("size-benchmark-$format").toFile()
    val database =
      DatabaseImpl(
        Unit,
        ResourceIndexer(SearchParamDefinitionsProviderImpl()),
        storageDirectory = storageDirectory.absolutePath,
        resourceStorageFormat = format,
      )
    try {
      corpus.chunked(WRITE_CHUNK).forEach { database.insertRemote(*it.toTypedArray()) }
    } finally {
      database.close()
    }

    val file = File(storageDirectory, DATABASE_FILE)
    val connection = BundledSQLiteDriver().open(file.absolutePath)
    return try {
      connection.execSQL("VACUUM")
      Measurement(
        format = format,
        fileBytes = file.length(),
        payloadBytes = connection.longOf(PAYLOAD_BYTES),
        indexRows = INDEX_TABLES.sumOf { connection.longOf("SELECT COUNT(*) FROM $it") },
      )
    } finally {
      connection.close()
    }
  }

  /** Alternating patients and observations referring back to them, from a fixed seed. */
  private fun corpus(count: Int): List<Resource> {
    val random = Random(SEED)
    return buildList {
      var patients = 0
      while (size < count) {
        add(patient(patients, random))
        if (size < count) add(observation(size, patients, random))
        patients++
      }
    }
  }

  /** Carries the narrative, identifiers, extensions and contact details a bulk export would. */
  private fun patient(index: Int, random: Random): Patient {
    val id = "benchmark-patient-$index"
    val family = SURNAMES[random.nextInt(SURNAMES.size)]
    val given = GIVEN_NAMES[random.nextInt(GIVEN_NAMES.size)]
    val city = CITIES[random.nextInt(CITIES.size)]
    return Patient(
      id = id,
      text =
        Narrative(
          status = Enumeration(value = Narrative.NarrativeStatus.Generated),
          div =
            Xhtml(
              value =
                "<div xmlns=\"http://www.w3.org/1999/xhtml\">Generated by the storage format " +
                  "benchmark. Record $index for $given $family of $city, seeded from $SEED so " +
                  "every run produces this same narrative. $given $family is a patient of the " +
                  "$city facility, registered through the community health programme, with " +
                  "routine vitals recorded at each visit and no known allergies on file.</div>",
            ),
        ),
      extension =
        listOf(
          Extension(
            url = "http://hl7.org/fhir/StructureDefinition/patient-birthPlace",
            value = Extension.Value.String(FhirString(value = city)),
          ),
          Extension(
            url = "http://hl7.org/fhir/StructureDefinition/patient-mothersMaidenName",
            value =
              Extension.Value.String(FhirString(value = SURNAMES[random.nextInt(SURNAMES.size)])),
          ),
          Extension(
            url = "http://hl7.org/fhir/StructureDefinition/patient-citizenship",
            value = Extension.Value.String(FhirString(value = "Kenya")),
          ),
          Extension(
            url = "http://synthetichealth.github.io/synthea/disability-adjusted-life-years",
            value = Extension.Value.Integer(FhirInteger(value = random.nextInt(0, 40))),
          ),
        ),
      identifier =
        listOf(
          Identifier(
            system = Uri(value = "https://github.com/synthetichealth/synthea"),
            value = FhirString(value = id),
          ),
          Identifier(
            use = Enumeration(value = Identifier.IdentifierUse.Official),
            type =
              CodeableConcept(
                coding =
                  listOf(
                    Coding(
                      system = Uri(value = "http://terminology.hl7.org/CodeSystem/v2-0203"),
                      code = Code(value = "MR"),
                      display = FhirString(value = "Medical Record Number"),
                    ),
                  ),
                text = FhirString(value = "Medical Record Number"),
              ),
            system = Uri(value = "http://hospital.smarthealthit.org"),
            value = FhirString(value = "mrn-$index"),
          ),
          Identifier(
            type =
              CodeableConcept(
                coding =
                  listOf(
                    Coding(
                      system = Uri(value = "http://terminology.hl7.org/CodeSystem/v2-0203"),
                      code = Code(value = "SS"),
                      display = FhirString(value = "Social Security Number"),
                    ),
                  ),
                text = FhirString(value = "Social Security Number"),
              ),
            system = Uri(value = "http://hl7.org/fhir/sid/us-ssn"),
            value = FhirString(value = "999-%02d-%04d".format(index % 100, index % 10000)),
          ),
        ),
      name =
        listOf(
          HumanName(
            use = Enumeration(value = HumanName.NameUse.Official),
            text = FhirString(value = "$given $family"),
            family = FhirString(value = family),
            given = listOf(FhirString(value = given), FhirString(value = "$given-middle")),
            prefix = listOf(FhirString(value = "Mr.")),
          ),
        ),
      maritalStatus =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus"),
                code = Code(value = "M"),
                display = FhirString(value = "Married"),
              ),
            ),
          text = FhirString(value = "Married"),
        ),
      telecom =
        listOf(ContactPoint(value = FhirString(value = "555-${100000 + random.nextInt(900000)}"))),
      gender =
        Enumeration(
          value =
            if (random.nextBoolean()) AdministrativeGender.Female else AdministrativeGender.Male,
        ),
      birthDate =
        Date(
          value =
            FhirDate.fromString(
              "19%02d-%02d-%02d"
                .format(
                  random.nextInt(50, 100) % 100,
                  random.nextInt(1, 13),
                  random.nextInt(1, 28),
                ),
            ),
        ),
      address =
        listOf(
          Address(
            line = listOf(FhirString(value = "${random.nextInt(1, 9999)} Benchmark Street")),
            city = FhirString(value = city),
            state = FhirString(value = "Nairobi"),
            postalCode = FhirString(value = "00%03d".format(random.nextInt(1000))),
            country = FhirString(value = "KE"),
          ),
        ),
    )
  }

  private fun observation(index: Int, patientIndex: Int, random: Random) =
    Observation(
      id = "benchmark-observation-$index",
      status = Enumeration(value = Observation.ObservationStatus.Final),
      category =
        listOf(
          CodeableConcept(
            coding =
              listOf(
                Coding(
                  system =
                    Uri(value = "http://terminology.hl7.org/CodeSystem/observation-category"),
                  code = Code(value = "vital-signs"),
                  display = FhirString(value = "Vital signs"),
                ),
              ),
          ),
        ),
      code =
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = "http://loinc.org"),
                code = Code(value = "8867-4"),
                display = FhirString(value = "Heart rate"),
              ),
            ),
          text = FhirString(value = "Heart rate"),
        ),
      subject =
        Reference(reference = FhirString(value = "Patient/benchmark-patient-$patientIndex")),
      effective =
        Observation.Effective.DateTime(
          DateTime(value = FhirDateTime.fromString("2026-0${random.nextInt(1, 10)}-15T09:30:00Z")),
        ),
      value = Observation.Value.Integer(FhirInteger(value = random.nextInt(55, 110))),
    )

  private fun change(value: Long, baseline: Long) =
    "%+.1f%%".format((value - baseline) * 100.0 / baseline)

  private suspend fun SQLiteConnection.longOf(sql: String): Long =
    prepare(sql).use {
      it.step()
      it.getLong(0)
    }

  private data class Measurement(
    val format: ResourceStorageFormat,
    val fileBytes: Long,
    val payloadBytes: Long,
    val indexRows: Long,
  )

  private companion object {
    const val SEED = 20261026
    const val WRITE_CHUNK = 200
    const val DATABASE_FILE = "resources.db"
    const val PAYLOAD_BYTES = "SELECT SUM(LENGTH(serializedResource)) FROM ResourceEntity"

    val SURNAMES = listOf("Achieng", "Barasa", "Cheruiyot", "Dzombo", "Kitoto", "Wanjiru")
    val GIVEN_NAMES = listOf("Amina", "Brian", "Chege", "Daudi", "Esther", "Faith")
    val CITIES = listOf("Kisumu", "Mombasa", "Nakuru", "Eldoret", "Nyeri", "Garissa")

    val INDEX_TABLES =
      listOf(
        "StringIndexEntity",
        "ReferenceIndexEntity",
        "TokenIndexEntity",
        "QuantityIndexEntity",
        "UriIndexEntity",
        "DateIndexEntity",
        "DateTimeIndexEntity",
        "NumberIndexEntity",
        "PositionIndexEntity",
      )
  }
}
