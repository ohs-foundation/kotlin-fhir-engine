# Copyright 2026 Open Health Stack Foundation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Unit tests for the pure logic in populate_benchmark_server."""

import unittest

import populate_benchmark_server as populate


def patient(id_):
    return {"resourceType": "Patient", "id": id_}


class TransactionBundleTest(unittest.TestCase):
    def test_wraps_resources_in_a_transaction_bundle(self):
        bundle = populate.transaction_bundle([patient("a"), patient("b")])

        self.assertEqual("Bundle", bundle["resourceType"])
        self.assertEqual("transaction", bundle["type"])
        self.assertEqual(2, len(bundle["entry"]))

    def test_puts_each_resource_at_its_own_id(self):
        # A POST would mint new ids and break the corpus's references.
        entry = populate.transaction_bundle([patient("abc")])["entry"][0]

        self.assertEqual("PUT", entry["request"]["method"])
        self.assertEqual("Patient/abc", entry["request"]["url"])
        self.assertEqual(patient("abc"), entry["resource"])

    def test_is_idempotent_across_runs(self):
        first = populate.transaction_bundle([patient("abc")])
        second = populate.transaction_bundle([patient("abc")])

        self.assertEqual(first, second)

    def test_refuses_a_resource_with_no_id(self):
        with self.assertRaises(populate.PopulateError):
            populate.transaction_bundle([{"resourceType": "Patient"}])

    def test_refuses_a_resource_with_no_type(self):
        with self.assertRaises(populate.PopulateError):
            populate.transaction_bundle([{"id": "abc"}])

    def test_makes_an_empty_bundle_from_nothing(self):
        self.assertEqual([], populate.transaction_bundle([])["entry"])


class UniqueByIdTest(unittest.TestCase):
    """The Synthea corpus repeats organization and practitioner ids."""

    def test_keeps_only_the_first_of_a_repeated_id(self):
        source = [patient("a"), patient("b"), patient("a")]

        self.assertEqual(
            [patient("a"), patient("b")], list(populate.unique_by_id(iter(source)))
        )

    def test_tells_apart_one_id_used_by_two_types(self):
        organization = {"resourceType": "Organization", "id": "a"}

        kept = list(populate.unique_by_id(iter([patient("a"), organization])))

        self.assertEqual([patient("a"), organization], kept)

    def test_passes_distinct_resources_through_untouched(self):
        source = [patient("a"), patient("b")]

        self.assertEqual(source, list(populate.unique_by_id(iter(source))))


class ChunkTest(unittest.TestCase):
    def test_splits_into_full_chunks_and_a_remainder(self):
        self.assertEqual(
            [[1, 2], [3, 4], [5]], list(populate.chunked(iter([1, 2, 3, 4, 5]), 2))
        )

    def test_yields_nothing_for_an_empty_source(self):
        self.assertEqual([], list(populate.chunked(iter([]), 2)))


class LoadOrderTest(unittest.TestCase):
    def test_loads_referenced_types_before_the_ones_pointing_at_them(self):
        # Encounter references Patient, which references Organization and Practitioner.
        order = populate.LOAD_ORDER

        self.assertLess(order.index("Organization"), order.index("Patient"))
        self.assertLess(order.index("Practitioner"), order.index("Patient"))
        self.assertLess(order.index("Patient"), order.index("Encounter"))


class OutcomeTest(unittest.TestCase):
    def test_counts_the_entries_a_server_accepted(self):
        response = {
            "resourceType": "Bundle",
            "type": "transaction-response",
            "entry": [
                {"response": {"status": "201 Created"}},
                {"response": {"status": "200 OK"}},
            ],
        }

        self.assertEqual((2, []), populate.outcome(response))

    def test_reports_the_entries_a_server_rejected(self):
        # A transaction is atomic, so one rejected entry means the chunk was not loaded.
        response = {
            "entry": [
                {"response": {"status": "201 Created"}},
                {"response": {"status": "422 Unprocessable Entity"}},
            ],
        }

        accepted, failures = populate.outcome(response)

        self.assertEqual(1, accepted)
        self.assertEqual(["422 Unprocessable Entity"], failures)


if __name__ == "__main__":
    unittest.main()
