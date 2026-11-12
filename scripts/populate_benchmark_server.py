#!/usr/bin/env python3
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
"""Loads the packaged Synthea corpus into a FHIR server, so `server.download` has something to read.

    benchmarks/tools/start-benchmark-server.sh
    scripts/populate_benchmark_server.py --base-url http://localhost:8080/fhir

Sent as transaction bundles. The corpus is exported with `--exporter.fhir.bulk_data=true`, so
references are literal `Type/id` and need no `urn:uuid` rewriting. Entries are `PUT` at their ids,
which keeps references valid and makes a repeated load idempotent.
"""

from __future__ import annotations

import argparse
import itertools
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = REPO_ROOT / "benchmarks/core/build/benchmark-data/synthea"

# Referenced types first. Mirrors NdjsonDataset.LOAD_ORDER.
LOAD_ORDER = ["Organization", "Practitioner", "Patient", "Encounter"]

# HAPI slows sharply on much larger bundles.
DEFAULT_BUNDLE_SIZE = 200


class PopulateError(Exception):
    """The corpus or the server is not in a state this script can work with."""


def chunked(source, size):
    """Fixed-size lists from an iterator, without holding the whole source."""
    while True:
        chunk = list(itertools.islice(source, size))
        if not chunk:
            return
        yield chunk


def unique_by_id(resources):
    """Drops repeats of a `Type/id` already seen.

    Synthea's bulk export repeats organizations and practitioners, and a transaction bundle
    with a duplicate id is rejected (HAPI-0535). Only the ids are retained.
    """
    seen = set()
    for resource in resources:
        key = (resource.get("resourceType"), resource.get("id"))
        if key in seen:
            continue
        seen.add(key)
        yield resource


def transaction_bundle(resources):
    """One transaction bundle that PUTs each resource at its own id."""
    entries = []
    for resource in resources:
        resource_type = resource.get("resourceType")
        resource_id = resource.get("id")
        if not resource_type:
            raise PopulateError(f"Resource with no resourceType: {str(resource)[:120]}")
        if not resource_id:
            raise PopulateError(f"{resource_type} with no id: {str(resource)[:120]}")
        reference = f"{resource_type}/{resource_id}"
        entries.append(
            {
                "fullUrl": reference,
                "resource": resource,
                "request": {"method": "PUT", "url": reference},
            },
        )
    return {"resourceType": "Bundle", "type": "transaction", "entry": entries}


def outcome(response):
    """How many entries the server took, and the statuses of any it refused."""
    accepted = 0
    failures = []
    for entry in response.get("entry", []):
        status = entry.get("response", {}).get("status", "")
        if status[:1] in ("2",):
            accepted += 1
        else:
            failures.append(status)
    return accepted, failures


def resources_in(path):
    """One parsed resource per line, streamed."""
    with open(path) as lines:
        for line in lines:
            if line.strip():
                yield json.loads(line)


def post_bundle(base_url, bundle, timeout):
    request = urllib.request.Request(
        base_url.rstrip("/") + "/",
        data=json.dumps(bundle).encode(),
        headers={"Content-Type": "application/fhir+json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as answer:
        return json.loads(answer.read().decode())


def load_type(base_url, path, bundle_size, timeout):
    """Loads one NDJSON file, returning how many resources the server accepted."""
    accepted = 0
    for chunk in chunked(unique_by_id(resources_in(path)), bundle_size):
        try:
            response = post_bundle(base_url, transaction_bundle(chunk), timeout)
        except urllib.error.HTTPError as error:
            body = error.read().decode()[:400]
            raise PopulateError(f"{path.name}: server returned {error.code}. {body}") from error
        except urllib.error.URLError as error:
            raise PopulateError(f"{path.name}: cannot reach {base_url}. {error.reason}") from error
        taken, failures = outcome(response)
        accepted += taken
        if failures:
            raise PopulateError(
                f"{path.name}: server refused {len(failures)} of {len(chunk)} entries. "
                f"First: {failures[0]}",
            )
        print(f"    {accepted:>8,} {path.stem}", end="\r", flush=True)
    return accepted


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8080/fhir")
    parser.add_argument("--data-dir", type=pathlib.Path, default=DATA_DIR)
    parser.add_argument("--bundle-size", type=int, default=DEFAULT_BUNDLE_SIZE)
    parser.add_argument(
        "--types", nargs="+", default=LOAD_ORDER, help="restrict to these resource types"
    )
    parser.add_argument("--timeout", type=float, default=300, help="seconds per bundle")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if not args.data_dir.is_dir():
        raise PopulateError(
            f"No packaged corpus at {args.data_dir}. "
            "Run ./gradlew :benchmarks:core:packageBenchmarkData first.",
        )
    try:
        urllib.request.urlopen(args.base_url.rstrip("/") + "/metadata", timeout=30).read(1)
    except OSError as error:
        raise PopulateError(f"No FHIR server answering at {args.base_url}. {error}") from error

    started = time.monotonic()
    total = 0
    for resource_type in args.types:
        path = args.data_dir / f"{resource_type}.ndjson"
        if not path.is_file():
            print(f"    {resource_type}: not in this corpus, skipping")
            continue
        loaded = load_type(args.base_url, path, args.bundle_size, args.timeout)
        total += loaded
        print(f"    {loaded:>8,} {resource_type}")
    elapsed = time.monotonic() - started
    rate = total / elapsed if elapsed else 0
    print(f"Loaded {total:,} resources in {elapsed:.0f}s ({rate:.0f}/s) into {args.base_url}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PopulateError as error:
        sys.exit(f"PopulateError: {error}")
    except KeyboardInterrupt:
        sys.exit("Interrupted. Loading is idempotent, so rerunning resumes safely.")
