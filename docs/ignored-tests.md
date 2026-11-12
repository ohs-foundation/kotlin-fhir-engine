# Ignored tests

Every test the KMP engine skips with `@Ignore`, and why. Each one shows a known bug in the
KMP engine or a library it uses. Fixing the bug and removing `@Ignore` makes the test run
again.

This page is generated from the test code. After adding or removing an `@Ignore`, run
`python3 scripts/ignored_tests.py`. CI fails when the page is out of date.

34 ignored tests.

| Test | Runs on | Why |
|---|---|---|
| `ResourceIndexerTest.index_dateTime_string` | every platform | fhir-path returns scheduledString as a plain kotlin String, which the dateTime indexer does not handle. Enable once the indexer parses plain String date-times. |
| `ResourceIndexerTest.index_gender` | every platform | fhir-path unwraps Enumeration/Code to a bare String, dropping the code system (e.g. http://hl7.org/fhir/administrative-gender). Enable once fhir-path preserves it. |
| `ResourceIndexerTest.index_quantity_money` | every platform | Money currency is indexed as the enum name "Eur" instead of its code "EUR". Enable once the indexer uses the currency code. |
| `ResourceIndexerTest.index_quantity_quantity_unit` | every platform | fhir-path unwraps Quantity to a lossy FhirPathQuantity that drops system/code, so QuantityIndex can't be reconstructed. Enable once fhir-path preserves Quantity system/code. |
| `ResourceIndexerTest.index_quantity_quantity_code_canonicalized` | every platform | fhir-path unwraps Quantity to a lossy FhirPathQuantity that drops system/code, so QuantityIndex can't be reconstructed. Enable once fhir-path preserves Quantity system/code. |
| `ResourceIndexerTest.index_quantity_quantity_code_notCanonicalized` | every platform | fhir-path unwraps Quantity to a lossy FhirPathQuantity that drops system/code, so QuantityIndex can't be reconstructed. Enable once fhir-path preserves Quantity system/code. |
| `ResourceIndexerTest.index_duplicateQuantity_deduplicateQuantityIndices` | every platform | fhir-path unwraps Quantity to a lossy FhirPathQuantity that drops system/code, so QuantityIndex can't be reconstructed. Enable once fhir-path preserves Quantity system/code. |
| `ResourceIndexerTest.index_quantity_observation_valueQuantity` | every platform | The search param paths use `Observation.component.value as Quantity`, and fhir-path's `as` throws on a collection with more than one item, so the whole param is skipped. Enable once `as` works on repeating elements. |
| `ResourceIndexerTest.index_invoice` | every platform | Money currency is indexed as the enum name "Eur" instead of its code "EUR", and coded values lose their system as in index_gender. Enable once both are fixed. |
| `ResourceIndexerTest.index_questionnaire` | every platform | fhir-path unwraps Enumeration/Code to a bare String, dropping the code system, so coded values are indexed with a null system. Enable once fhir-path preserves it. |
| `ResourceIndexerTest.index_patient` | every platform | fhir-path unwraps Enumeration/Code to a bare String, dropping the code system, so coded values are indexed with a null system. Enable once fhir-path preserves it. |
| `ResourceIndexerTest.index_location` | every platform | fhir-path unwraps Enumeration/Code to a bare String, dropping the code system, so coded values are indexed with a null system. Enable once fhir-path preserves it. |
| `SearchTest.search_filter_quantity_canonical_match` | every platform | kotlin-fhir-path canonicalizes mg to g1, where android-fhir's UCUM service gives g, so a database indexed by android-fhir would not match. Enable once the canonical unit matches. |
| `DownloaderImplTest.downloaderShouldKeepTheCauseOfAFailureWithoutAMessage` | every platform | ResourceSyncException keeps only the exception message, so a failure without one reaches the app as Unknown Exception. Enable once the exception itself is kept. |
| `DatabaseImplSearchTest.search_date_equals_fractionalSeconds` | desktop | The indexer throws ArithmeticException on a dateTime with fractional seconds, so the insert fails. |
| `DatabaseImplSearchTest.search_dateTime_equals_sameSecond` | desktop | A dateTime is indexed as a one second range but eq searches a single instant, so nothing matches. |
| `DatabaseImplSearchTest.search_token_emptySystem_matchesCodeOnly` | desktop | An empty system drops the system clause but still binds its argument, so SQLite throws column index out of range. |
| `DatabaseImplSearchTest.search_quantity_equal` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_not_equal` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_less` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_less_no_match` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_greater` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_greater_no_match` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_less_or_equal` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_less_or_equal_no_match` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_greater_or_equal` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_greater_or_equal_no_match` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_starts_after` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_starts_after_no_match` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_ends_before` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_ends_before_no_match` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_quantity_canonical` | desktop | kotlin-fhir-path returns valueQuantity as a FhirPathQuantity, which the indexer drops, so nothing matches. |
| `DatabaseImplSearchTest.search_patient_has_taken_influenza_vaccine_in_India` | desktop | Coded values are indexed without their system, so a status filter with a system matches nothing. |
| `DatabaseImplSearchTest.search_patient_and_revInclude_encounters_sorted_by_date_descending` | desktop | Coded values are indexed without their system, so a status filter with a system matches nothing. |
