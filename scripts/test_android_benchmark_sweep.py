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
"""Unit tests for the pure logic in android_benchmark_sweep."""

import argparse
import tempfile
import unittest
from pathlib import Path

import android_benchmark_sweep as sweep


SERVER = "http://localhost:8080/fhir"


def write_catalogue(directory, **files):
    for name, body in files.items():
        (directory / f"{name}.kt").write_text(body)


class DiscoverWorkloadsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def test_reads_ids_from_the_catalogue_sources(self):
        write_catalogue(
            self.dir,
            CrudWorkloads='override val id = "crud.create_batch"\noverride val id = "crud.update"',
            SearchWorkloads='search("search.patient_by_family") { env ->',
            SyncWorkloads='override val id = "sync.download_batch"',
            ServerWorkloads='override val id = "server.upload_creates"',
        )

        self.assertEqual(
            [
                "crud.create_batch",
                "crud.update",
                "search.patient_by_family",
                "sync.download_batch",
                "server.upload_creates",
            ],
            sweep.discover_workloads(self.dir),
        )

    def test_keeps_each_id_once_when_it_appears_twice(self):
        write_catalogue(
            self.dir,
            CrudWorkloads='"crud.create_batch"\nmeasure("crud.create_batch")',
            SearchWorkloads='search("search.patient_by_family")',
            SyncWorkloads='"sync.download_batch"',
            ServerWorkloads='"server.upload_creates"',
        )

        self.assertEqual(1, sweep.discover_workloads(self.dir).count("crud.create_batch"))

    def test_refuses_a_catalogue_with_no_workloads_for_a_device_group(self):
        write_catalogue(
            self.dir,
            CrudWorkloads='override val id = "crud.create_batch"',
            SearchWorkloads="// none yet",
            SyncWorkloads='override val id = "sync.download_batch"',
            ServerWorkloads='override val id = "server.upload_creates"',
        )

        with self.assertRaises(sweep.CatalogueError) as raised:
            sweep.discover_workloads(self.dir)
        self.assertIn("search", str(raised.exception))


class SelectionTest(unittest.TestCase):
    ALL = [
        "crud.create_batch",
        "search.patient_by_family",
        "sync.download_batch",
        "server.upload_creates",
    ]

    def test_device_workloads_exclude_the_server_group_without_a_url(self):
        self.assertEqual(
            ["crud.create_batch", "search.patient_by_family", "sync.download_batch"],
            sweep.device_workloads(self.ALL),
        )

    def test_device_workloads_include_the_server_group_given_a_url(self):
        self.assertEqual(self.ALL, sweep.device_workloads(self.ALL, SERVER))

    def test_server_workloads_are_reported_rather_than_dropped(self):
        self.assertEqual(["server.upload_creates"], sweep.undriveable_workloads(self.ALL))

    def test_nothing_is_undriveable_once_a_server_url_is_given(self):
        self.assertEqual([], sweep.undriveable_workloads(self.ALL, SERVER))

    def test_a_requested_server_workload_is_rejected_without_a_url(self):
        with self.assertRaises(sweep.SelectionError) as raised:
            sweep.resolve_requested(["server.upload_creates"], self.ALL)
        self.assertIn("--server", str(raised.exception))

    def test_a_requested_server_workload_is_accepted_given_a_url(self):
        self.assertEqual(
            ["server.upload_creates"],
            sweep.resolve_requested(["server.upload_creates"], self.ALL, SERVER),
        )

    def test_an_unknown_workload_is_rejected(self):
        with self.assertRaises(sweep.SelectionError):
            sweep.resolve_requested(["crud.nope"], self.ALL)

    def test_class_for_group(self):
        self.assertTrue(
            sweep.class_for("search.patient_by_family").endswith("FhirEngineSearchMacrobenchmark")
        )

    def test_class_for_the_server_group(self):
        self.assertTrue(
            sweep.class_for("server.upload_creates").endswith("FhirEngineServerMacrobenchmark")
        )


class DeviceRecordTest(unittest.TestCase):
    PROPS = {
        "ro.product.model": "SM-X135G",
        "ro.product.manufacturer": "samsung",
        "ro.build.version.release": "16",
        "ro.build.version.sdk": "36",
        "ro.build.fingerprint": "samsung/gta11dxx/gta11:16/ABC/123:user/release-keys",
        "dalvik.vm.heapgrowthlimit": "256m",
        "dalvik.vm.heapsize": "512m",
    }

    def test_records_what_a_reader_needs_to_place_the_numbers(self):
        record = sweep.device_record(self.PROPS)

        self.assertEqual("SM-X135G", record["model"])
        self.assertEqual("samsung", record["manufacturer"])
        self.assertEqual("16", record["androidVersion"])
        self.assertEqual(36, record["apiLevel"])
        self.assertEqual("512m", record["heapSizeLimit"])

    def test_omits_the_serial_number(self):
        self.assertNotIn("serial", sweep.device_record(self.PROPS))

    def test_survives_a_property_the_device_does_not_expose(self):
        record = sweep.device_record({"ro.product.model": "SM-X135G"})

        self.assertEqual("SM-X135G", record["model"])
        self.assertIsNone(record["apiLevel"])


class GradleCommandTest(unittest.TestCase):
    def args(self, **overrides):
        defaults = dict(
            population=50,
            profile="standard",
            reuse_corpus=False,
            server=None,
            mix=None,
        )
        defaults.update(overrides)
        return argparse.Namespace(**defaults)

    def test_omits_the_server_argument_when_no_url_is_given(self):
        command = sweep.gradle_command("crud.create_batch", self.args(), 1, 60)
        self.assertFalse([c for c in command if ".server=" in c])

    def test_leaves_the_driver_installed_between_workloads(self):
        # Otherwise AGP uninstalls the driver and its seeded database after every workload.
        command = sweep.gradle_command("search.patient_by_family", self.args(), 1, 60)

        self.assertIn(
            "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true", command
        )

    def test_forwards_the_clinical_mix_when_given(self):
        command = sweep.gradle_command("search.patient_by_family", self.args(mix="off"), 1, 60)

        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.mix=off", command)

    def test_omits_the_mix_argument_by_default(self):
        command = sweep.gradle_command("search.patient_by_family", self.args(), 1, 60)

        self.assertFalse([c for c in command if ".mix=" in c])

    def test_forwards_the_server_url_as_an_instrumentation_argument(self):
        command = sweep.gradle_command("server.upload_creates", self.args(server=SERVER), 1, 60)
        self.assertIn(
            f"-Pandroid.testInstrumentationRunnerArguments.server={SERVER}", command
        )


class LocalPortTest(unittest.TestCase):
    def test_a_localhost_url_names_its_port(self):
        self.assertEqual(8080, sweep.local_port("http://localhost:8080/fhir"))

    def test_the_loopback_address_is_local_too(self):
        self.assertEqual(8080, sweep.local_port("http://127.0.0.1:8080/fhir"))

    def test_a_local_url_without_a_port_uses_the_scheme_default(self):
        self.assertEqual(80, sweep.local_port("http://localhost/fhir"))

    def test_a_remote_url_needs_no_forwarding(self):
        self.assertIsNone(sweep.local_port("https://hapi.example.org/fhir"))


BENCHMARK_DATA = {
    "benchmarks": [
        {
            "name": "crud",
            "className": "dev.ohs.fhir.engine.benchmark.macro.FhirEngineCrudMacrobenchmark",
            "metrics": {
                "crud.create_batchCount": {"median": 1.0, "runs": [1.0]},
                "crud.create_batchSumMs": {"median": 330.6, "runs": [330.6]},
            },
        }
    ]
}


class MetricsTest(unittest.TestCase):
    def test_reads_the_sum_for_the_requested_workload(self):
        metrics = sweep.metrics_for(BENCHMARK_DATA, "crud.create_batch")

        self.assertAlmostEqual(330.6, metrics["sum_ms_median"])
        self.assertEqual([330.6], metrics["runs"])
        self.assertEqual(1, metrics["iterations"])

    def test_ignores_metrics_belonging_to_another_workload(self):
        self.assertIsNone(sweep.metrics_for(BENCHMARK_DATA, "crud.update")["sum_ms_median"])


class ClassifyTest(unittest.TestCase):
    def test_a_clean_run_with_a_measurement_is_ok(self):
        outcome = sweep.classify(
            returncode=0, timed_out=False, logcat="", gradle_log="", sum_ms=330.6
        )
        self.assertEqual("ok", outcome.status)

    def test_a_clean_run_that_measured_nothing_is_not_ok(self):
        outcome = sweep.classify(
            returncode=0, timed_out=False, logcat="", gradle_log="", sum_ms=0.0
        )
        self.assertEqual("no-metric", outcome.status)

    def test_an_out_of_memory_kill_outranks_the_timeout_it_causes(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=True,
            logcat="E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate",
            gradle_log="did not finish within 600000ms",
            sum_ms=None,
        )
        self.assertEqual("oom", outcome.status)

    def test_a_low_memory_killer_kill_of_the_driver_counts_as_out_of_memory(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=True,
            logcat="lmkd: Kill 'dev.ohs.fhir.engine.benchmark.app' (1234), uid 10234",
            gradle_log="",
            sum_ms=None,
        )
        self.assertEqual("oom", outcome.status)

    def test_a_kill_of_the_test_process_counts_as_out_of_memory(self):
        # Reading back a large Perfetto trace can get the instrumentation process killed.
        logcat = (
            "I lmkd    : Reclaim 'dev.ohs.fhir.engine.benchmark.macro' (29177), uid 10292, "
            "oom_score_adj 0, state 19 to free 142432kB rss; reason: min watermark is breached"
        )

        outcome = sweep.classify(1, False, logcat, "Process crashed.", None)

        self.assertEqual("oom", outcome.status)

    def test_a_kill_of_an_unrelated_package_is_not_our_out_of_memory(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=True,
            logcat="lmkd: Kill 'com.example.other' (1234), uid 10999",
            gradle_log="",
            sum_ms=None,
        )
        self.assertEqual("timeout", outcome.status)

    def test_a_run_past_the_wall_clock_is_a_timeout(self):
        outcome = sweep.classify(
            returncode=None, timed_out=True, logcat="", gradle_log="", sum_ms=None
        )
        self.assertEqual("timeout", outcome.status)

    def test_any_other_non_zero_exit_is_a_failure_and_keeps_its_reason(self):
        outcome = sweep.classify(
            returncode=1,
            timed_out=False,
            logcat="",
            gradle_log="IllegalStateException: Workload crud.create_batch failed. Status: failed",
            sum_ms=None,
        )
        self.assertEqual("failed", outcome.status)
        self.assertIn("IllegalStateException", outcome.detail)


class DatasetSummaryTest(unittest.TestCase):
    def test_reads_a_plain_corpus_line(self):
        logcat = "I BenchmarkDriver: dataset=synthea population=500 fingerprint=76df0a368ccf69c5"

        self.assertEqual(
            {"kind": "synthea", "population": 500, "fingerprint": "76df0a368ccf69c5"},
            sweep.dataset_summary(logcat),
        )

    def test_keeps_the_whole_fingerprint_of_a_generated_dataset(self):
        # AugmentedDataset appends the clinical mix after a hyphen.
        logcat = (
            "I BenchmarkDriver: dataset=synthea+generated population=500 "
            "fingerprint=76df0a368ccf69c5-g8x2s20260819"
        )

        self.assertEqual(
            {
                "kind": "synthea+generated",
                "population": 500,
                "fingerprint": "76df0a368ccf69c5-g8x2s20260819",
            },
            sweep.dataset_summary(logcat),
        )

    def test_two_mixes_over_one_corpus_are_told_apart(self):
        line = (
            "I BenchmarkDriver: dataset=synthea+generated population=500 fingerprint=abc123-g%sx2s1"
        )

        eight = sweep.dataset_summary(line % 8)["fingerprint"]
        four = sweep.dataset_summary(line % 4)["fingerprint"]

        self.assertNotEqual(eight, four)

    def test_says_nothing_when_the_driver_never_logged_one(self):
        self.assertIsNone(sweep.dataset_summary("nothing here"))


class CorpusNoteTest(unittest.TestCase):
    COUNTS = {"Patient": 50000, "Encounter": 41770, "Organization": 37699}

    def test_flags_a_workload_whose_resource_type_is_absent_from_the_corpus(self):
        # Procedure, because nothing generates it.
        self.assertEqual(
            "corpus has no Procedure",
            sweep.corpus_note("search.procedure_by_code", self.COUNTS),
        )

    def test_flags_the_absent_type_of_a_workload_that_names_two(self):
        self.assertEqual(
            "corpus has no Procedure",
            sweep.corpus_note("search.patient_revinclude_procedure", self.COUNTS),
        )

    def test_says_nothing_about_a_type_the_run_generates(self):
        # Generated types are in the database but not in the corpus manifest.
        self.assertIsNone(
            sweep.corpus_note(
                "search.observation_by_code", {"Patient": 1000}, generated=("Observation",)
            )
        )

    def test_still_flags_a_type_nothing_provides(self):
        self.assertEqual(
            "corpus has no Condition",
            sweep.corpus_note(
                "search.patient_has_condition", {"Patient": 1000}, generated=("Observation",)
            ),
        )

    def test_says_nothing_when_every_type_the_workload_names_is_present(self):
        self.assertIsNone(sweep.corpus_note("search.patient_by_family", self.COUNTS))


if __name__ == "__main__":
    unittest.main()
