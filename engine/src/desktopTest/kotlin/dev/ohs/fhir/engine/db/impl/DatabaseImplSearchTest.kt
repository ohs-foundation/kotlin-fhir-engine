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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.engine.SearchResult
import dev.ohs.fhir.engine.db.Database
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinition
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.index.SearchParamType
import dev.ohs.fhir.engine.resourceType
import dev.ohs.fhir.engine.search.DateClientParam
import dev.ohs.fhir.engine.search.LOCAL_LAST_UPDATED
import dev.ohs.fhir.engine.search.NumberClientParam
import dev.ohs.fhir.engine.search.Operation
import dev.ohs.fhir.engine.search.Order
import dev.ohs.fhir.engine.search.QuantityClientParam
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.StringClientParam
import dev.ohs.fhir.engine.search.StringFilterModifier
import dev.ohs.fhir.engine.search.TokenClientParam
import dev.ohs.fhir.engine.search.execute
import dev.ohs.fhir.engine.search.getQuery
import dev.ohs.fhir.engine.search.has
import dev.ohs.fhir.engine.search.include
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.engine.search.searchClock
import dev.ohs.fhir.engine.testStorageDirectory
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.Person
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.RiskAssessment
import dev.ohs.fhir.model.r4.SearchParameter.SearchComparator
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private const val QUANTITY_NOT_INDEXED =
  "kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches"

/**
 * The search execution tests of android-fhir's `DatabaseImplTest`. Each one stores resources in a
 * real [DatabaseImpl] and runs a search over them, where `SearchTest` only compares the SQL a
 * search compiles to.
 *
 * KMP adaptations:
 * - HAPI types become kotlin-fhir types, with the fields kotlin-fhir requires (statuses, subjects)
 *   filled in where android-fhir left them out.
 * - Ids compare bare, not as `Type/id`.
 * - android-fhir's `SearchResultCorrespondence` becomes [shallow].
 */
class DatabaseImplSearchTest {
  private lateinit var database: Database

  @BeforeTest
  fun setUp() = runTest {
    database = buildDatabase()
    database.clearDatabase()
    database.insert(TEST_PATIENT_1)
  }

  @AfterTest
  fun tearDown() = runTest {
    database.clearDatabase()
    database.close()
  }

  private fun buildDatabase(customParams: List<SearchParamDefinition> = emptyList()) =
    DatabaseImpl(
      Unit,
      ResourceIndexer(
        SearchParamDefinitionsProviderImpl(
          customParams = customParams.groupBy { it.path.substringBefore(".") },
        ),
      ),
      storageDirectory = testStorageDirectory(),
    )

  @Test
  fun search_sortDescending_twoVeryCloseFloatingPointNumbers_orderedCorrectly() = runTest {
    val smallerId = "risk_assessment_1"
    val largerId = "risk_assessment_2"
    database.insert(
      riskAssessment(smallerId, "0.3"),
      riskAssessment(largerId, "0.30000000001"),
    )

    val ids =
      searchIds(
        Search(ResourceType.RiskAssessment).apply { sort(PROBABILITY, Order.DESCENDING) },
      )

    assertEquals(listOf(largerId, smallerId), ids)
  }

  @Test
  fun search_number_equal() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Eq, "100"))
  }

  @Test
  fun search_number_equal_no_match() = runTest {
    database.insert(riskAssessment("1", "100.5"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Eq, "100"))
  }

  @Test
  fun search_number_notEqual() = runTest {
    database.insert(riskAssessment("1", "99.0"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Ne, "100"))
  }

  @Test
  fun search_number_notEqual_no_match() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Ne, "100"))
  }

  @Test
  fun search_number_greater() = runTest {
    database.insert(riskAssessment("1", "100"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Gt, "99.5"))
  }

  @Test
  fun search_number_greater_no_match() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Gt, "99.5"))
  }

  @Test
  fun search_number_greaterThanEqual() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Ge, "99.5"))
  }

  @Test
  fun search_number_greaterThanEqual_no_match() = runTest {
    database.insert(riskAssessment("1", "99.0"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Ge, "99.5"))
  }

  @Test
  fun search_number_less() = runTest {
    database.insert(riskAssessment("1", "99.0"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Lt, "99.5"))
  }

  @Test
  fun search_number_less_no_match() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Lt, "99.5"))
  }

  @Test
  fun search_number_lessThanEquals() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Le, "99.5"))
  }

  @Test
  fun search_number_lessThanEquals_no_match() = runTest {
    database.insert(riskAssessment("1", "100"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Le, "99.5"))
  }

  @Test
  fun search_decimal_endsBefore() = runTest {
    database.insert(riskAssessment("1", "99.0"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Eb, "99.5"))
  }

  @Test
  fun search_decimal_endsBefore_no_match() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Eb, "99.5"))
  }

  @Test
  fun search_decimal_startAfter() = runTest {
    database.insert(riskAssessment("1", "100"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Sa, "99.5"))
  }

  @Test
  fun search_decimal_startAfter_no_match() = runTest {
    database.insert(riskAssessment("1", "99.5"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Sa, "99.5"))
  }

  @Test
  fun search_number_approximate() = runTest {
    database.insert(riskAssessment("1", "93"))

    assertEquals(listOf("1"), searchProbability(SearchComparator.Ap, "100"))
  }

  @Test
  fun search_number_approximate_no_match() = runTest {
    database.insert(riskAssessment("1", "120"))

    assertEquals(emptyList(), searchProbability(SearchComparator.Ap, "100"))
  }

  @Test
  fun search_dateTime_approximate() = runTest {
    database.insert(deceasedPatient("2013-03-16T10:00:00-05:30"))

    val ids = withMockClock { searchDeathDate(SearchComparator.Ap, "2013-03-14") }

    assertEquals(listOf("1"), ids)
  }

  @Test
  fun search_dateTime_approximate_no_match() = runTest {
    database.insert(deceasedPatient("2013-03-16T10:00:00-05:30"))

    val ids = withMockClock { searchDeathDate(SearchComparator.Ap, "2020-03-14") }

    assertEquals(emptyList(), ids)
  }

  @Test
  fun search_date_approximate() = runTest {
    database.insert(Patient(id = "1", birthDate = Date(value = FhirDate.fromString("2013-03-16"))))

    val ids = withMockClock { searchBirthDate(SearchComparator.Ap, "2013-03-14") }

    assertEquals(listOf("1"), ids)
  }

  @Test
  fun search_date_approximate_no_match() = runTest {
    database.insert(Patient(id = "1", birthDate = Date(value = FhirDate.fromString("2013-03-16"))))

    val ids = withMockClock { searchBirthDate(SearchComparator.Ap, "2020-03-14") }

    assertEquals(emptyList(), ids)
  }

  @Test
  fun search_date_starts_after() = runTest {
    database.insert(deceasedPatient("2013-03-23T10:00:00-05:30"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Sa, "2013-03-14"))
  }

  @Test
  fun search_date_starts_after_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-13T10:00:00-05:30"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Sa, "2013-03-14"))
  }

  @Test
  fun search_date_ends_before() = runTest {
    // android-fhir stores 2013-03-13T01:00:00 with no offset. kotlin-fhir requires an offset, as
    // FHIR does, so this test stores it in UTC.
    database.insert(deceasedPatient("2013-03-13T01:00:00Z"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Eb, "2013-03-14"))
  }

  @Test
  fun search_date_ends_before_noMatch() = runTest {
    // android-fhir stores 2014-03-13T01:00:00 with no offset. kotlin-fhir requires an offset, as
    // FHIR does, so this test stores it in UTC.
    database.insert(deceasedPatient("2014-03-13T01:00:00Z"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Eb, "2013-03-14"))
  }

  @Test
  fun search_date_not_equals() = runTest {
    database.insert(deceasedPatient("2013-03-13T10:00:00-05:30"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Ne, "2013-03-14"))
  }

  @Test
  fun search_date_not_equals_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Ne, "2013-03-14"))
  }

  @Test
  fun search_date_equals() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Eq, "2013-03-14"))
  }

  @Test
  fun search_date_equals_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-13T10:00:00-05:30"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Eq, "2013-03-14"))
  }

  @Test
  fun search_date_greater() = runTest {
    database.insert(deceasedPatient("2013-03-15"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Gt, "2013-03-14"))
  }

  @Test
  fun search_date_greater_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Gt, "2013-03-14"))
  }

  @Test
  fun search_date_greater_or_equal() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Ge, "2013-03-14"))
  }

  @Test
  fun search_date_greater_or_equal_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-13T10:00:00-05:30"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Ge, "2013-03-14"))
  }

  @Test
  fun search_date_less() = runTest {
    database.insert(deceasedPatient("2013-03-13"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Lt, "2013-03-14"))
  }

  @Test
  fun search_date_less_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(emptyList(), searchDeathDate(SearchComparator.Lt, "2013-03-14"))
  }

  @Test
  fun search_date_less_or_equal() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Le, "2013-03-14"))
  }

  @Test
  fun search_date_less_or_equal_noMatch() = runTest {
    database.insert(deceasedPatient("2013-03-14T23:00:00-05:30"))

    assertEquals(
      emptyList(),
      searchDeathDate(SearchComparator.Le, "2013-03-14T00:00:00-00:00"),
    )
  }

  @Ignore(
    "The indexer throws ArithmeticException on a dateTime with fractional seconds, so the insert fails",
  )
  @Test
  fun search_date_equals_fractionalSeconds() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00.123-05:30"))

    assertEquals(listOf("1"), searchDeathDate(SearchComparator.Eq, "2013-03-14"))
  }

  @Ignore(
    "A dateTime is indexed as a one second range but eq searches a single instant, so nothing matches",
  )
  @Test
  fun search_dateTime_equals_sameSecond() = runTest {
    database.insert(deceasedPatient("2013-03-14T10:00:00-05:30"))

    assertEquals(
      listOf("1"),
      searchDeathDate(SearchComparator.Eq, "2013-03-14T10:00:00-05:30"),
    )
  }

  @Ignore(
    "An empty system drops the system clause but still binds its argument, so SQLite throws column index out of range",
  )
  @Test
  fun search_token_emptySystem_matchesCodeOnly() = runTest {
    database.insert(immunization("1", coding("http://hl7.org/fhir/sid/cvx", "140", "Influenza")))

    val ids =
      searchIds(
        Search(ResourceType.Immunization).apply {
          filter(TokenClientParam("vaccine-code"), { value = of(coding("", "140", "Influenza")) })
        },
      )

    assertEquals(listOf("1"), ids)
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_equal() = runTest {
    database.insert(quantityObservation("5.403"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Eq, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_not_equal() = runTest {
    database.insert(quantityObservation("5.403"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Ne, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_less() = runTest {
    database.insert(quantityObservation("5.3"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Lt, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_less_no_match() = runTest {
    database.insert(quantityObservation("5.4035"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Lt, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_greater() = runTest {
    database.insert(quantityObservation("5.5"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Gt, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_greater_no_match() = runTest {
    database.insert(quantityObservation("5.3"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Gt, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_less_or_equal() = runTest {
    database.insert(quantityObservation("5.3"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Le, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_less_or_equal_no_match() = runTest {
    database.insert(quantityObservation("5.5"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Le, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_greater_or_equal() = runTest {
    database.insert(quantityObservation("5.5"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Ge, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_greater_or_equal_no_match() = runTest {
    database.insert(quantityObservation("5.3"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Ge, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_starts_after() = runTest {
    database.insert(quantityObservation("5.5"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Sa, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_starts_after_no_match() = runTest {
    database.insert(quantityObservation("5.3"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Sa, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_ends_before() = runTest {
    database.insert(quantityObservation("5.3"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Eb, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_ends_before_no_match() = runTest {
    database.insert(quantityObservation("5.5"))

    assertEquals(emptyList(), searchValueQuantity(SearchComparator.Eb, "5.403"))
  }

  @Ignore(QUANTITY_NOT_INDEXED)
  @Test
  fun search_quantity_canonical() = runTest {
    database.insert(quantityObservation("5.403"))

    assertEquals(listOf("1"), searchValueQuantity(SearchComparator.Eq, "5403", unit = "mg"))
  }

  @Ignore(
    "Coded values are indexed without their system, so a status filter with a system matches nothing",
  )
  @Test
  fun search_patient_has_taken_influenza_vaccine_in_India() = runTest {
    val patient =
      Patient(
        id = "100",
        gender = Enumeration(value = AdministrativeGender.Male),
        address = listOf(Address(country = FhirString(value = "IN"))),
      )
    val immunization =
      immunization(
        id = null,
        vaccine =
          coding(
            "http://hl7.org/fhir/sid/cvx",
            "140",
            "Influenza, seasonal, injectable, preservative free",
          ),
        patient = "Patient/${patient.id}",
      )
    database.insert(patient, TEST_PATIENT_1, immunization)

    val ids =
      searchIds(
        Search(ResourceType.Patient).apply {
          has<Immunization>(ReferenceClientParam("patient")) {
            filter(
              TokenClientParam("vaccine-code"),
              {
                value =
                  of(
                    coding(
                      "http://hl7.org/fhir/sid/cvx",
                      "140",
                      "Influenza, seasonal, injectable, preservative free",
                    ),
                  )
              },
            )

            // The code system of Immunization.status.
            filter(
              TokenClientParam("status"),
              {
                value = of(coding("http://hl7.org/fhir/event-status", "completed", "Body Weight"))
              },
            )
          }

          filter(
            StringClientParam("address-country"),
            {
              modifier = StringFilterModifier.MATCHES_EXACTLY
              value = "IN"
            },
          )
        },
      )

    assertEquals(listOf("100"), ids)
  }

  @Test
  fun search_patient_return_single_patient_who_has_diabetic_careplan() = runTest {
    val patient = Patient(id = "100", gender = Enumeration(value = AdministrativeGender.Male))
    // This careplan has 2 patient references. One as subject and other as a performer.
    // The search should only find the subject Patient.
    val diabetesPlan =
      coding("http://snomed.info/sct", "698360004", "Diabetes self management plan")
    val carePlan =
      CarePlan(
        status = Enumeration(value = CarePlan.RequestStatus.Active),
        intent = Enumeration(value = CarePlan.CarePlanIntent.Plan),
        subject = reference("Patient/${patient.id}"),
        activity =
          listOf(
            CarePlan.Activity(
              detail =
                CarePlan.Activity.Detail(
                  status = Enumeration(value = CarePlan.CarePlanActivityStatus.Not_Started),
                  performer = listOf(reference("Patient/${TEST_PATIENT_1.id}")),
                ),
            ),
          ),
        category = listOf(CodeableConcept(coding = listOf(diabetesPlan))),
      )
    database.insert(patient, TEST_PATIENT_1, carePlan)

    val ids =
      searchIds(
        Search(ResourceType.Patient).apply {
          has<CarePlan>(ReferenceClientParam("subject")) {
            filter(TokenClientParam("category"), { value = of(diabetesPlan) })
          }
        },
      )

    assertEquals(listOf("100"), ids)
  }

  @Test
  fun search_filter_param_values_disjunction_covid_immunization_records() = runTest {
    database.insert(*COVID_IMMUNIZATIONS.toTypedArray())

    val result =
      database.search<Immunization>(
        Search(ResourceType.Immunization)
          .apply {
            filter(
              TokenClientParam("vaccine-code"),
              {
                value =
                  of(
                    coding(
                      "http://id.who.int/icd11/mms",
                      "XM1NL1",
                      "COVID-19 vaccine, inactivated virus",
                    ),
                  )
              },
              {
                value =
                  of(
                    coding(
                      "http://id.who.int/icd11/mms",
                      "XM5DF6",
                      "COVID-19 vaccine, inactivated virus",
                    ),
                  )
              },
              operation = Operation.OR,
            )
          }
          .getQuery(),
      )

    assertEquals(
      setOf("XM1NL1", "XM5DF6"),
      result.map { it.resource.vaccineCode.coding.first().code?.value }.toSet(),
    )
    assertEquals(2, result.size)
  }

  @Test
  fun test_search_multiple_param_disjunction_covid_immunization_records() = runTest {
    database.insert(*COVID_IMMUNIZATIONS.toTypedArray())

    val result =
      database.search<Immunization>(
        Search(ResourceType.Immunization)
          .apply {
            filter(
              TokenClientParam("vaccine-code"),
              { value = of(coding("http://id.who.int/icd11/mms", "XM1NL1", "")) },
            )

            filter(
              TokenClientParam("vaccine-code"),
              { value = of(coding("http://id.who.int/icd11/mms", "XM5DF6", "")) },
            )
            operation = Operation.OR
          }
          .getQuery(),
      )

    assertEquals(
      listOf("XM1NL1", "XM5DF6"),
      result.map { it.resource.vaccineCode.coding.first().code?.value },
    )
  }

  @Test
  fun test_search_multiple_param_conjunction_with_multiple_values_disjunction() = runTest {
    database.insert(
      namedPatient("patient-01", "John", "Doe"),
      namedPatient("patient-02", "Jane", "Doe"),
      namedPatient("patient-03", "John", "Roe"),
      namedPatient("patient-04", "Jane", "Roe"),
      namedPatient("patient-05", "Rocky", "Balboa"),
    )

    val result =
      database.search<Patient>(
        Search(ResourceType.Patient)
          .apply {
            filter(
              StringClientParam("given"),
              {
                value = "John"
                modifier = StringFilterModifier.MATCHES_EXACTLY
              },
              {
                value = "Jane"
                modifier = StringFilterModifier.MATCHES_EXACTLY
              },
              operation = Operation.OR,
            )

            filter(
              StringClientParam("family"),
              {
                value = "Doe"
                modifier = StringFilterModifier.MATCHES_EXACTLY
              },
              {
                value = "Roe"
                modifier = StringFilterModifier.MATCHES_EXACTLY
              },
              operation = Operation.OR,
            )

            operation = Operation.AND
          }
          .getQuery(),
      )

    assertEquals(
      setOf("John Doe", "Jane Doe", "John Roe", "Jane Roe"),
      result.map { it.resource.name.first().singleString() }.toSet(),
    )
    assertEquals(4, result.size)
  }

  @Test
  fun search_patient_with_extension_as_search_param() = runTest {
    val maidenNameSearchParameter =
      SearchParamDefinition(
        name = "mothers-maiden-name",
        type = SearchParamType.STRING,
        path =
          "Patient.extension('http://hl7.org/fhir/StructureDefinition/patient-mothersMaidenName').value.as(String)",
      )
    // Get rid of the default database and create one with search params
    database.clearDatabase()
    database.close()
    database = buildDatabase(listOf(maidenNameSearchParameter))
    database.insert(DARCY_SMITH)

    val result =
      database.search<Patient>(
        Search(ResourceType.Patient)
          .apply {
            filter(
              StringClientParam("mothers-maiden-name"),
              {
                value = "Marca"
                modifier = StringFilterModifier.MATCHES_EXACTLY
              },
            )
          }
          .getQuery(),
      )

    assertTrue("Darcy Smith" in result.map { it.resource.name.first().singleString() })
  }

  @Test
  fun search_patient_with_custom_value_as_search_param() = runTest {
    val identifierPartialSearchParameter =
      SearchParamDefinition(
        name = "identifierPartial",
        type = SearchParamType.STRING,
        path = "Patient.identifier.value",
      )
    // Get rid of the default database and create one with search params
    database.clearDatabase()
    database.close()
    database = buildDatabase(listOf(identifierPartialSearchParameter))
    database.insert(DARCY_SMITH)

    val result =
      database.search<Patient>(
        Search(ResourceType.Patient)
          .apply {
            filter(
              StringClientParam("identifierPartial"),
              {
                value = "OfficialIdentifier_"
                modifier = StringFilterModifier.STARTS_WITH
              },
            )
          }
          .getQuery(),
      )

    assertTrue("Darcy Smith" in result.map { it.resource.name.first().singleString() })
  }

  @Test
  fun search_patient_with_local_lastUpdated() = runTest {
    // _local_lastUpdated is indexed in milliseconds, so each write waits for the next millisecond
    // to
    // keep the order free of ties.
    database.insert(Patient(id = "patient-test-001"))
    awaitNextMillisecond()
    database.insert(Patient(id = "patient-test-002"))
    awaitNextMillisecond()
    database.insert(Patient(id = "patient-test-003"))
    awaitNextMillisecond()
    database.update(
      Patient(id = "patient-test-002", gender = Enumeration(value = AdministrativeGender.Female)),
    )

    val ids =
      searchIds(
        Search(ResourceType.Patient).apply {
          sort(DateClientParam(LOCAL_LAST_UPDATED), Order.DESCENDING)
        },
      )

    // TEST_PATIENT_1 from setUp also matches, so only the order of these three is checked.
    assertEquals(
      listOf("patient-test-002", "patient-test-003", "patient-test-001"),
      ids.filter { it in setOf("patient-test-001", "patient-test-002", "patient-test-003") },
    )
  }

  @Test
  fun search_patient_and_include_practitioners() = runTest {
    val patient01 = namedPatient("pa-01", "James", "Gorden", gps = listOf("gp-01", "gp-02"))
    val patient02 = namedPatient("pa-02", "James", "Bond", gps = listOf("gp-02", "gp-03"))
    val gp01 = practitioner("gp-01", "Practitioner-01", "General-01", active = true)
    val gp02 = practitioner("gp-02", "Practitioner-02", "General-02", active = false)
    val gp03 = practitioner("gp-03", "Practitioner-03", "General-03", active = true)
    database.insertRemote(patient01, patient02, gp01, gp02, gp03)

    val result =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "James"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )

          include<Practitioner>(ReferenceClientParam("general-practitioner")) {
            filter(TokenClientParam("active"), { value = of(true) })
          }
        }
        .execute<Patient>(database)

    assertEquals(
      setOf(
        SearchResult(patient01, mapOf("general-practitioner" to listOf(gp01)), null).shallow(),
        SearchResult(patient02, mapOf("general-practitioner" to listOf(gp03)), null).shallow(),
      ),
      result.map { it.shallow() }.toSet(),
    )
    assertEquals(2, result.size)
  }

  @Test
  fun search_patient_and_revInclude_conditions() = runTest {
    val patient01 = namedPatient("pa-01", "James", "Gorden", gps = listOf("gp-01"))
    val patient02 = namedPatient("pa-02", "James", "Bond", gps = listOf("gp-02"))
    val con1 = condition("con-01", DIABETES, "Patient/pa-01")
    val con2 = condition("con-02", HYPERTENSION, "Patient/pa-01")
    val con3 = condition("con-03", MIGRAINE, "Patient/pa-02")
    database.insertRemote(patient01, patient02, con1, con2, con3)

    val result =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "James"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )
          revInclude<Condition>(ReferenceClientParam("subject")) {
            filter(TokenClientParam("code"), { value = of(DIABETES) })
            filter(TokenClientParam("code"), { value = of(MIGRAINE) })
            operation = Operation.OR
          }
        }
        .execute<Patient>(database)

    assertEquals(
      setOf(
        SearchResult(patient01, null, mapOf(CONDITION_SUBJECT to listOf(con1))).shallow(),
        SearchResult(patient02, null, mapOf(CONDITION_SUBJECT to listOf(con3))).shallow(),
      ),
      result.map { it.shallow() }.toSet(),
    )
    assertEquals(2, result.size)
  }

  @Test
  fun search_patient_with_reference_resources() = runTest {
    val gps = listOf("gp-01", "gp-02", "gp-03")
    val patients =
      listOf(
        namedPatient("pa-01", "James", "Gorden", gps = gps, organization = "org-01"),
        namedPatient("pa-02", "James", "Bond", gps = gps, organization = "org-02"),
        namedPatient("pa-03", "James", "Doe", gps = gps, organization = "org-03"),
      )
    val practitioners =
      listOf(
        practitioner("gp-01", "Practitioner-01", "General-01", active = true),
        practitioner("gp-02", "Practitioner-02", "General-02", active = true),
        practitioner("gp-03", "Practitioner-03", "General-03", active = false),
      )
    val organizations =
      listOf(
        organization("org-01", "Organization-01", active = true),
        organization("org-02", "Organization-02", active = true),
        organization("org-03", "Organization-03", active = false),
      )
    val conditions =
      listOf("pa-01", "pa-02", "pa-03").flatMap { patient ->
        listOf(
          condition("con-01-$patient", DIABETES, "Patient/$patient"),
          condition("con-02-$patient", HYPERTENSION, "Patient/$patient"),
          condition("con-03-$patient", MIGRAINE, "Patient/$patient"),
        )
      }
    // android-fhir builds these periods with HAPI's zero based months, so DateType(2023, 2, 1) is
    // 2023-03-01.
    val encounters =
      listOf("pa-01", "pa-02", "pa-03").flatMap { patient ->
        listOf(
          encounter("en-01-$patient", "Patient/$patient", "2023-03-01", "2023-12-01"),
          encounter("en-02-$patient", "Patient/$patient", "2023-03-01", "2023-12-01"),
          encounter("en-03-$patient", "Patient/$patient", "2022-03-01", "2022-12-01"),
        )
      }
    val resources: Map<String, Resource> =
      (patients + practitioners + organizations + conditions + encounters).associateBy { it.id!! }
    database.insertRemote(*resources.values.toTypedArray())

    // Each of the 3 patients has 3 GPs, 3 conditions and 3 encounters, and only 2 of each match.
    val result =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "James"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )

          include<Practitioner>(ReferenceClientParam("general-practitioner")) {
            filter(TokenClientParam("active"), { value = of(true) })
            filter(
              StringClientParam("family"),
              {
                value = "Practitioner"
                modifier = StringFilterModifier.STARTS_WITH
              },
            )
            operation = Operation.AND
          }
          include<Organization>(ReferenceClientParam("organization")) {
            filter(
              StringClientParam("name"),
              {
                value = "Organization"
                modifier = StringFilterModifier.STARTS_WITH
              },
            )
            filter(TokenClientParam("active"), { value = of(true) })
            operation = Operation.AND
          }

          revInclude<Condition>(ReferenceClientParam("subject")) {
            filter(TokenClientParam("code"), { value = of(DIABETES) })
            filter(TokenClientParam("code"), { value = of(MIGRAINE) })
            operation = Operation.OR
          }
          revInclude<Encounter>(ReferenceClientParam("subject")) {
            filter(
              DateClientParam("date"),
              {
                value = of(FhirDateTime.fromString("2023-01-01")!!)
                prefix = SearchComparator.Ge
              },
            )
          }
        }
        .execute<Patient>(database)

    fun expected(patient: String, organization: String?) =
      SearchResult(
          resources.getValue(patient),
          mapOf(
            "general-practitioner" to
              listOf(resources.getValue("gp-01"), resources.getValue("gp-02")),
          ) +
            listOfNotNull(organization).associate {
              "organization" to listOf(resources.getValue(it))
            },
          mapOf(
            CONDITION_SUBJECT to
              listOf(resources.getValue("con-01-$patient"), resources.getValue("con-03-$patient")),
            ENCOUNTER_SUBJECT to
              listOf(resources.getValue("en-01-$patient"), resources.getValue("en-02-$patient")),
          ),
        )
        .shallow(inOrder = false)

    assertEquals(
      setOf(expected("pa-01", "org-01"), expected("pa-02", "org-02"), expected("pa-03", null)),
      result.map { it.shallow(inOrder = false) }.toSet(),
    )
    assertEquals(3, result.size)
  }

  @Test
  fun search_patient_and_include_practitioners_sorted_by_family_descending() = runTest {
    val patient01 =
      namedPatient(
        "pa-01",
        "James",
        "Gorden",
        gps = listOf("gp-01", "gp-02", "gp-04"),
        organization = "org-01",
      )
    val patient02 =
      namedPatient(
        "pa-02",
        "James",
        "Bond",
        gps = listOf("gp-02", "gp-03", "gp-04"),
        organization = "org-03",
      )
    val gp01 = practitioner("gp-01", "Practitioner-01", "General-01", active = true)
    val gp02 = practitioner("gp-02", "Practitioner-02", "General-02", active = true)
    val gp03 = practitioner("gp-03", "Practitioner-03", "General-03", active = true)
    val gp04 = practitioner("gp-04", "Practitioner-04", "General-04", active = false)
    database.insertRemote(patient01, patient02, gp01, gp02, gp03, gp04)

    val result =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "James"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )

          include<Practitioner>(ReferenceClientParam("general-practitioner")) {
            filter(TokenClientParam("active"), { value = of(true) })
            sort(StringClientParam("family"), Order.DESCENDING)
          }
        }
        .execute<Patient>(database)

    assertEquals(
      setOf(
        SearchResult(patient01, mapOf("general-practitioner" to listOf(gp02, gp01)), null)
          .shallow(),
        SearchResult(patient02, mapOf("general-practitioner" to listOf(gp03, gp02)), null)
          .shallow(),
      ),
      result.map { it.shallow() }.toSet(),
    )
    assertEquals(2, result.size)
  }

  @Test
  fun search_patient_and_revinclude_person_should_map_common_person_to_all_matching_patients() =
    runTest {
      val person1 = person("person-1", "First", "Patient/pa-01", "Patient/pa-02")
      val person2 = person("person-2", "Second", "Patient/pa-02", "Patient/pa-03")
      val person3 = person("person-3", "Third", "Patient/pa-01", "Patient/pa-03")
      val patient01 = namedPatient("pa-01", "James", "Gorden")
      val patient02 = namedPatient("pa-02", "James", "Bond")
      val patient03 = namedPatient("pa-03", "Jamie", "Bond")
      database.insert(person1, person2, person3, patient01, patient02, patient03)

      val result =
        Search(ResourceType.Patient)
          .apply {
            filter(
              StringClientParam("given"),
              {
                value = "Jam"
                modifier = StringFilterModifier.STARTS_WITH
              },
            )

            revInclude(ResourceType.Person, ReferenceClientParam("link")) {
              sort(StringClientParam("name"), Order.ASCENDING)
            }
          }
          .execute<Patient>(database)

      val personLink = ResourceType.Person to "link"
      assertEquals(
        setOf(
          SearchResult(patient01, null, mapOf(personLink to listOf(person1, person3))).shallow(),
          SearchResult(patient02, null, mapOf(personLink to listOf(person1, person2))).shallow(),
          SearchResult(patient03, null, mapOf(personLink to listOf(person2, person3))).shallow(),
        ),
        result.map { it.shallow() }.toSet(),
      )
      assertEquals(3, result.size)
    }

  @Ignore(
    "Coded values are indexed without their system, so a status filter with a system matches nothing",
  )
  @Test
  fun search_patient_and_revInclude_encounters_sorted_by_date_descending() = runTest {
    val patient01 = namedPatient("pa-01", "James", "Gorden", gps = listOf("gp-01"))
    val patient02 = namedPatient("pa-02", "James", "Bond", gps = listOf("gp-02"))
    val arrived = Encounter.EncounterStatus.Arrived
    val cancelled = Encounter.EncounterStatus.Cancelled

    // Patient 1's encounters. android-fhir uses HAPI's zero based months, so each month here is one
    // higher.
    val enc1_1 = encounter("enc1-01", "Patient/pa-01", "2010-02-01", "2010-02-02", arrived)
    val enc1_2 = encounter("enc1-02", "Patient/pa-01", "2010-03-01", "2010-03-02", cancelled)
    val enc1_3 = encounter("enc1-03", "Patient/pa-01", "2010-04-01", "2010-04-02", arrived)
    val enc1_4 = encounter("enc1-04", "Patient/pa-01", "2010-05-01", "2010-05-02", arrived)

    // Patient 2's encounters.
    val enc2_1 = encounter("enc2-01", "Patient/pa-02", "2020-02-01", "2020-02-02", arrived)
    val enc2_2 = encounter("enc2-02", "Patient/pa-02", "2020-03-01", "2020-03-02", cancelled)
    val enc2_3 = encounter("enc2-03", "Patient/pa-02", "2020-04-01", "2020-04-02", arrived)
    val enc2_4 = encounter("enc2-04", "Patient/pa-02", "2020-05-01", "2020-05-02", arrived)

    database.insertRemote(
      patient01,
      patient02,
      enc1_1,
      enc1_2,
      enc1_3,
      enc1_4,
      enc2_1,
      enc2_2,
      enc2_3,
      enc2_4,
    )

    val result =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("given"),
            {
              value = "James"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )

          revInclude<Encounter>(ReferenceClientParam("subject")) {
            filter(
              TokenClientParam("status"),
              { value = of(coding("http://hl7.org/fhir/encounter-status", arrived.code, "")) },
            )
            sort(DateClientParam("date"), Order.DESCENDING)
          }
        }
        .execute<Patient>(database)

    assertEquals(
      setOf(
        SearchResult(patient01, null, mapOf(ENCOUNTER_SUBJECT to listOf(enc1_4, enc1_3, enc1_1)))
          .shallow(),
        SearchResult(patient02, null, mapOf(ENCOUNTER_SUBJECT to listOf(enc2_4, enc2_3, enc2_1)))
          .shallow(),
      ),
      result.map { it.shallow() }.toSet(),
    )
    assertEquals(2, result.size)
  }

  // https://github.com/google/android-fhir/issues/2512
  @Test
  fun included_results_sort_ascending_should_have_distinct_resources() = runTest {
    // Sorting by an index with several values per resource joins one row per value. The query
    // must still return each resource once.
    val group = group("group", "Patient/p1", "Patient/p2")
    val p1 = cooper("p1", "3", "1")
    val p2 = cooper("p2", "2", "4")
    database.insert(group, p1, p2)

    val ascendingResult =
      Search(ResourceType.Group)
        .apply {
          include<Patient>(ReferenceClientParam("member")) {
            sort(StringClientParam("given"), Order.ASCENDING)
          }
        }
        .execute<Patient>(database)

    assertTrue(
      SearchResult(group, mapOf("member" to listOf(p1, p2)), null).shallow() in
        ascendingResult.map { it.shallow() },
    )
  }

  // https://github.com/google/android-fhir/issues/2512
  @Test
  fun included_results_sort_descending_should_have_distinct_resources() = runTest {
    val group = group("group", "Patient/p1", "Patient/p2")
    val p1 = cooper("p1", "3", "1")
    val p2 = cooper("p2", "2", "4")
    database.insert(group, p1, p2)

    val descendingResult =
      Search(ResourceType.Group)
        .apply {
          include<Patient>(ReferenceClientParam("member")) {
            sort(StringClientParam("given"), Order.DESCENDING)
          }
        }
        .execute<Patient>(database)

    assertTrue(
      SearchResult(group, mapOf("member" to listOf(p2, p1)), null).shallow() in
        descendingResult.map { it.shallow() },
    )
  }

  @Test
  fun revIncluded_results_sort_ascending_should_have_distinct_resources() = runTest {
    val practitioner = practitioner("practitioner-1", "Cooper", "James")
    val p1 = cooper("p1", "3", "1", gp = "Practitioner/practitioner-1")
    val p2 = cooper("p2", "2", "4", gp = "Practitioner/practitioner-1")
    database.insert(practitioner, p1, p2)

    val ascendingResult =
      Search(ResourceType.Practitioner)
        .apply {
          revInclude<Patient>(ReferenceClientParam("general-practitioner")) {
            sort(StringClientParam("given"), Order.ASCENDING)
          }
        }
        .execute<Patient>(database)

    assertTrue(
      SearchResult(practitioner, null, mapOf(PATIENT_GENERAL_PRACTITIONER to listOf(p1, p2)))
        .shallow() in ascendingResult.map { it.shallow() },
    )
  }

  @Test
  fun revIncluded_results_sort_descending_should_have_distinct_resources() = runTest {
    val practitioner = practitioner("practitioner-1", "Cooper", "James")
    val p1 = cooper("p1", "3", "1", gp = "Practitioner/practitioner-1")
    val p2 = cooper("p2", "2", "4", gp = "Practitioner/practitioner-1")
    database.insert(practitioner, p1, p2)

    val descendingResult =
      Search(ResourceType.Practitioner)
        .apply {
          revInclude<Patient>(ReferenceClientParam("general-practitioner")) {
            sort(StringClientParam("given"), Order.DESCENDING)
          }
        }
        .execute<Patient>(database)

    assertTrue(
      SearchResult(practitioner, null, mapOf(PATIENT_GENERAL_PRACTITIONER to listOf(p2, p1)))
        .shallow() in descendingResult.map { it.shallow() },
    )
  }

  @Test
  fun included_and_revIncluded_results_should_have_distinct_resources() = runTest {
    // The patient has several given names and the encounter several locations. Sorting the include
    // by given and the revInclude by location-period must still return one copy of each.
    val group = group("group", "Patient/multiple-first-names")
    val patient =
      Patient(
        id = "multiple-first-names",
        name = listOf(humanName("LastName", "FirstName-01", "FirstName-02", "FirstName-03")),
      )
    val encounter =
      locationsEncounter(
        "encounter-multiple-locations",
        "Location/1" to ("2024-03-13T10:00:00-05:30" to "2024-03-13T10:30:00-05:30"),
        "Location/2" to ("2024-03-13T11:00:00-05:30" to "2024-03-13T11:30:00-05:30"),
        "Location/3" to ("2024-03-13T09:00:00-05:30" to "2024-03-13T09:30:00-05:30"),
      )
    database.insert(group, patient, encounter)

    val result =
      Search(ResourceType.Group)
        .apply {
          include<Patient>(ReferenceClientParam("member")) {
            filter(
              StringClientParam("given"),
              {
                value = "FirstName"
                modifier = StringFilterModifier.STARTS_WITH
              },
            )

            sort(StringClientParam("given"), Order.ASCENDING)
          }

          revInclude<Encounter>(ReferenceClientParam("subject")) {
            sort(DateClientParam("location-period"), Order.ASCENDING)
          }
        }
        .execute<Patient>(database)

    assertTrue(
      SearchResult(
          group,
          mapOf("member" to listOf(patient)),
          mapOf(ENCOUNTER_SUBJECT to listOf(encounter)),
        )
        .shallow() in result.map { it.shallow() },
    )
  }

  @Test
  fun sort_ascending_repeated_values_with_string_param_should_return_distinct_values() = runTest {
    // p1's smallest given name is its second, so a sort on the first indexed value misorders it.
    val p1 = cooper("p1", "3", "1")
    val p2 = cooper("p2", "2", "4")
    database.insert(p1, p2)

    val ascendingResult =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("family"),
            {
              value = "Cooper"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )
          sort(StringClientParam("given"), Order.ASCENDING)
        }
        .execute<Patient>(database)

    assertEquals(
      listOf(SearchResult(p1, null, null).shallow(), SearchResult(p2, null, null).shallow()),
      ascendingResult.map { it.shallow() },
    )
  }

  @Test
  fun sort_descending_repeated_values_with_string_param_should_return_distinct_values() = runTest {
    val p1 = cooper("p1", "3", "1")
    val p2 = cooper("p2", "2", "4")
    database.insert(p1, p2)

    val descendingResult =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("family"),
            {
              value = "Cooper"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )
          sort(StringClientParam("given"), Order.DESCENDING)
        }
        .execute<Patient>(database)

    assertEquals(
      listOf(SearchResult(p2, null, null).shallow(), SearchResult(p1, null, null).shallow()),
      descendingResult.map { it.shallow() },
    )
  }

  @Test
  fun sort_ascending_repeated_values_with_date_param_should_return_distinct_values() = runTest {
    val (e1, e2) = repeatedLocationEncounters()
    database.insert(e1, e2)

    val ascendingResult =
      Search(ResourceType.Encounter)
        .apply { sort(DateClientParam("location-period"), Order.ASCENDING) }
        .execute<Encounter>(database)

    assertEquals(
      listOf(SearchResult(e1, null, null).shallow(), SearchResult(e2, null, null).shallow()),
      ascendingResult.map { it.shallow() },
    )
  }

  @Test
  fun sort_descending_repeated_values_with_date_param_should_return_distinct_values() = runTest {
    val (e1, e2) = repeatedLocationEncounters()
    database.insert(e1, e2)

    val descendingResult =
      Search(ResourceType.Encounter)
        .apply { sort(DateClientParam("location-period"), Order.DESCENDING) }
        .execute<Encounter>(database)

    assertEquals(
      listOf(SearchResult(e2, null, null).shallow(), SearchResult(e1, null, null).shallow()),
      descendingResult.map { it.shallow() },
    )
  }

  @Test
  fun sort_ascending_repeated_values_with_numeric_param_should_return_distinct_values() = runTest {
    val (r1, r2, r3) = repeatedProbabilityRiskAssessments()
    database.insert(r1, r2, r3)

    val ascendingResult =
      Search(ResourceType.RiskAssessment)
        .apply {
          filter(
            PROBABILITY,
            {
              value = BigDecimal.parseString("0.4")
              prefix = SearchComparator.Gt
            },
          )
          sort(PROBABILITY, Order.ASCENDING)
        }
        .execute<RiskAssessment>(database)

    assertEquals(
      listOf(SearchResult(r1, null, null).shallow(), SearchResult(r2, null, null).shallow()),
      ascendingResult.map { it.shallow() },
    )
  }

  @Test
  fun sort_descending_repeated_values_with_numeric_param_should_return_distinct_values() = runTest {
    val (r1, r2, r3) = repeatedProbabilityRiskAssessments()
    database.insert(r1, r2, r3)

    val descendingResult =
      Search(ResourceType.RiskAssessment)
        .apply {
          filter(
            PROBABILITY,
            {
              value = BigDecimal.parseString("0.4")
              prefix = SearchComparator.Gt
            },
          )
          sort(PROBABILITY, Order.DESCENDING)
        }
        .execute<RiskAssessment>(database)

    assertEquals(
      listOf(SearchResult(r2, null, null).shallow(), SearchResult(r1, null, null).shallow()),
      descendingResult.map { it.shallow() },
    )
  }

  @Test
  fun sort_resource_with_null_sort_index_value_but_matching_filter_should_be_included() = runTest {
    // HAPI's DateType(2020, 4, 2) has a zero based month, so it is 2020-05-02.
    val p1 = cooper("p1", "4", "1", birthDate = "2020-05-02")
    val p2 = cooper("p2", "2", "5", birthDate = "2010-05-02")
    val p3 = cooper("p3", "3", "6")
    database.insert(p1, p2, p3)

    val ascendingResult =
      Search(ResourceType.Patient)
        .apply {
          filter(
            StringClientParam("family"),
            {
              value = "Cooper"
              modifier = StringFilterModifier.MATCHES_EXACTLY
            },
          )
          sort(DateClientParam("birthdate"), Order.ASCENDING)
        }
        .execute<Patient>(database)

    assertEquals(
      listOf(
        SearchResult(p2, null, null).shallow(),
        SearchResult(p1, null, null).shallow(),
        SearchResult(p3, null, null).shallow(),
      ),
      ascendingResult.map { it.shallow() },
    )
  }

  private suspend fun searchIds(search: Search): List<String?> =
    database.search<Resource>(search.getQuery()).map { it.resource.id }

  private suspend fun searchProbability(prefix: SearchComparator, value: String) =
    searchIds(
      Search(ResourceType.RiskAssessment).apply {
        filter(
          PROBABILITY,
          {
            this.prefix = prefix
            this.value = BigDecimal.parseString(value)
          },
        )
      },
    )

  private suspend fun searchDeathDate(prefix: SearchComparator, value: String) =
    searchIds(
      Search(ResourceType.Patient).apply {
        filter(
          DateClientParam("death-date"),
          {
            this.value = of(FhirDateTime.fromString(value)!!)
            this.prefix = prefix
          },
        )
      },
    )

  private suspend fun searchBirthDate(prefix: SearchComparator, value: String) =
    searchIds(
      Search(ResourceType.Patient).apply {
        filter(
          DateClientParam("birthdate"),
          {
            this.value = of(FhirDate.fromString(value)!!)
            this.prefix = prefix
          },
        )
      },
    )

  private suspend fun searchValueQuantity(
    prefix: SearchComparator,
    value: String,
    unit: String = "g",
  ) =
    searchIds(
      Search(ResourceType.Observation).apply {
        filter(
          QuantityClientParam("value-quantity"),
          {
            this.prefix = prefix
            this.value = BigDecimal.parseString(value)
            this.system = "http://unitsofmeasure.org"
            this.unit = unit
          },
        )
      },
    )

  /** Spins until the clock passes the current millisecond. */
  private fun awaitNextMillisecond() {
    val now = Clock.System.now().toEpochMilliseconds()
    while (Clock.System.now().toEpochMilliseconds() <= now) {}
  }

  /** Fixes "now" for approximate date searches, as android-fhir's `DateProvider` does. */
  private suspend fun <T> withMockClock(block: suspend () -> T): T {
    val now = Instant.fromEpochMilliseconds(MOCK_EPOCH_TIME_STAMP)
    searchClock =
      object : Clock {
        override fun now() = now
      }
    return try {
      block()
    } finally {
      searchClock = Clock.System
    }
  }

  /**
   * android-fhir's `SearchResultCorrespondence`: a result compared by the type and id of the
   * resource and of each included and revIncluded resource. With [inOrder] false the included lists
   * compare ignoring order, as in its `SearchResultCorrespondenceUnorderedIncludeRevInclude`.
   */
  private fun SearchResult<*>.shallow(inOrder: Boolean = true): List<Any?> {
    fun List<Resource>.keys(): Any =
      map { "${it.resourceType}/${it.id}" }.let { if (inOrder) it else it.sorted() }
    return listOf(
      (resource as Resource).let { "${it.resourceType}/${it.id}" },
      included?.mapValues { (_, resources) -> resources.keys() },
      revIncluded?.mapValues { (_, resources) -> resources.keys() },
    )
  }

  private fun HumanName.singleString() =
    (given.map { it.value } + family?.value).filterNotNull().joinToString(" ")

  companion object {
    private const val MOCK_EPOCH_TIME_STAMP = 1628516301000
    private const val TEST_PATIENT_1_ID = "test_patient_1"
    private val TEST_PATIENT_1 =
      Patient(id = TEST_PATIENT_1_ID, gender = Enumeration(value = AdministrativeGender.Male))

    private val PROBABILITY = NumberClientParam("probability")
    private val CONDITION_SUBJECT = ResourceType.Condition to "subject"
    private val ENCOUNTER_SUBJECT = ResourceType.Encounter to "subject"
    private val PATIENT_GENERAL_PRACTITIONER = ResourceType.Patient to "general-practitioner"

    private val DIABETES = codeableConcept("http://snomed.info/sct", "44054006", "Diabetes")
    private val HYPERTENSION =
      codeableConcept("http://snomed.info/sct", "827069000", "Hypertension stage 1")
    private val MIGRAINE = codeableConcept("http://snomed.info/sct", "37796009", "Migraine")

    private val COVID_IMMUNIZATIONS =
      listOf(
        immunization(
          "immunization-1",
          coding("http://id.who.int/icd11/mms", "XM1NL1", "COVID-19 vaccine, inactivated virus"),
        ),
        immunization(
          "immunization-2",
          coding(
            "http://id.who.int/icd11/mms",
            "XM5DF6",
            "COVID-19 vaccine, live attenuated virus",
          ),
        ),
        immunization(
          "immunization-3",
          coding("http://id.who.int/icd11/mms", "XM6AT1", "COVID-19 vaccine, DNA based"),
        ),
        immunization(
          "immunization-4",
          coding(
            "http://hl7.org/fhir/sid/cvx",
            "140",
            "Influenza, seasonal, injectable, preservative free",
          ),
        ),
      )

    private val DARCY_SMITH =
      Patient(
        identifier =
          listOf(
            Identifier(
              system = Uri(value = "https://custom-identifier-namespace"),
              value = FhirString(value = "OfficialIdentifier_DarcySmith_0001"),
            ),
          ),
        name =
          listOf(
            HumanName(
              use = Enumeration(value = HumanName.NameUse.Official),
              family = FhirString(value = "Smith"),
              given = listOf(FhirString(value = "Darcy")),
            ),
          ),
        gender = Enumeration(value = AdministrativeGender.Female),
        birthDate = Date(value = FhirDate.fromString("1970-01-01")),
        extension =
          listOf(
            Extension(
              url = "http://hl7.org/fhir/StructureDefinition/patient-mothersMaidenName",
              value = Extension.Value.String(FhirString(value = "Marca")),
            ),
          ),
      )

    private fun reference(value: String) = Reference(reference = FhirString(value = value))

    private fun coding(system: String, code: String, display: String) =
      Coding(
        system = Uri(value = system),
        code = Code(value = code),
        display = FhirString(value = display),
      )

    private fun codeableConcept(system: String, code: String, display: String) =
      CodeableConcept(coding = listOf(coding(system, code, display)))

    private fun dateTime(value: String) = DateTime(value = FhirDateTime.fromString(value))

    private fun humanName(family: String, vararg given: String) =
      HumanName(
        family = FhirString(value = family),
        given = given.map { FhirString(value = it) },
      )

    private fun riskAssessment(id: String, vararg probability: String) =
      RiskAssessment(
        id = id,
        status = Enumeration(value = RiskAssessment.ObservationStatus.Final),
        subject = reference("Patient/risk-patient"),
        prediction =
          probability.map {
            RiskAssessment.Prediction(
              probability =
                RiskAssessment.Prediction.Probability.Decimal(
                  Decimal(value = FhirDecimal.fromString(it)),
                ),
            )
          },
      )

    private fun repeatedProbabilityRiskAssessments() =
      listOf(
        riskAssessment("ris-01", "0.8", "0.5"),
        riskAssessment("ris-02", "0.6", "0.9"),
        riskAssessment("ris-03", "0.2", "0.4"),
      )

    private fun deceasedPatient(deceased: String) =
      Patient(id = "1", deceased = Patient.Deceased.DateTime(dateTime(deceased)))

    private fun quantityObservation(value: String) =
      Observation(
        id = "1",
        status = Enumeration(value = Observation.ObservationStatus.Final),
        code = CodeableConcept(),
        value =
          Observation.Value.Quantity(
            Quantity(
              value = Decimal(value = FhirDecimal.fromString(value)),
              system = Uri(value = "http://unitsofmeasure.org"),
              code = Code(value = "g"),
            ),
          ),
      )

    private fun immunization(id: String?, vaccine: Coding, patient: String = "Patient/unknown") =
      Immunization(
        id = id,
        status = Enumeration(value = Immunization.ImmunizationStatusCodes.Completed),
        vaccineCode = CodeableConcept(coding = listOf(vaccine)),
        patient = reference(patient),
        occurrence = Immunization.Occurrence.DateTime(dateTime("2021-01-01")),
      )

    private fun namedPatient(
      id: String,
      given: String,
      family: String,
      gps: List<String> = emptyList(),
      organization: String? = null,
    ) =
      Patient(
        id = id,
        name = listOf(humanName(family, given)),
        generalPractitioner = gps.map { reference("Practitioner/$it") },
        managingOrganization = organization?.let { reference("Organization/$it") },
      )

    private fun cooper(
      id: String,
      vararg given: String,
      gp: String? = null,
      birthDate: String? = null,
    ) =
      Patient(
        id = id,
        name = listOf(humanName("Cooper", *given)),
        generalPractitioner = listOfNotNull(gp?.let { reference(it) }),
        birthDate = birthDate?.let { Date(value = FhirDate.fromString(it)) },
      )

    private fun practitioner(id: String, family: String, given: String, active: Boolean? = null) =
      Practitioner(
        id = id,
        name = listOf(humanName(family, given)),
        active = active?.let { FhirBoolean(value = it) },
      )

    private fun organization(id: String, name: String, active: Boolean) =
      Organization(id = id, name = FhirString(value = name), active = FhirBoolean(value = active))

    private fun condition(id: String, code: CodeableConcept, subject: String) =
      Condition(id = id, code = code, subject = reference(subject))

    private fun encounter(
      id: String,
      subject: String,
      start: String,
      end: String,
      status: Encounter.EncounterStatus = Encounter.EncounterStatus.Finished,
    ) =
      Encounter(
        id = id,
        status = Enumeration(value = status),
        `class` = Coding(),
        subject = reference(subject),
        period = Period(start = dateTime(start), end = dateTime(end)),
      )

    private fun locationsEncounter(
      id: String,
      vararg locations: Pair<String, Pair<String, String>>,
    ) =
      Encounter(
        id = id,
        status = Enumeration(value = Encounter.EncounterStatus.Finished),
        `class` = Coding(),
        subject = reference("Group/group"),
        location =
          locations.map { (location, period) ->
            Encounter.Location(
              location = reference(location),
              period = Period(start = dateTime(period.first), end = dateTime(period.second)),
            )
          },
      )

    private fun repeatedLocationEncounters() =
      listOf(
        locationsEncounter(
          "encounter-multiple-locations-1",
          "Location/3" to ("2024-03-13T09:00:00-05:30" to "2024-03-13T09:30:00-05:30"),
          "Location/2" to ("2024-03-13T11:00:00-05:30" to "2024-03-13T11:30:00-05:30"),
        ),
        locationsEncounter(
          "encounter-multiple-locations-2",
          "Location/1" to ("2024-03-13T10:00:00-05:30" to "2024-03-13T10:30:00-05:30"),
          "Location/2" to ("2024-03-13T11:30:00-05:30" to "2024-03-13T12:00:00-05:30"),
        ),
      )

    private fun group(id: String, vararg members: String) =
      Group(
        id = id,
        type = Enumeration(value = Group.GroupType.Person),
        `actual` = FhirBoolean(value = true),
        member = members.map { Group.Member(entity = reference(it)) },
      )

    private fun person(id: String, given: String, vararg links: String) =
      Person(
        id = id,
        name = listOf(humanName("Person", given)),
        link = links.map { Person.Link(target = reference(it)) },
      )
  }
}
