#!/usr/bin/env bash
#
# Uploads the packaged Synthea data to the benchmark server, so `server.download` has something to
# download. Skip it if you only care about the upload workloads.
#
#   ./gradlew :benchmarks:core:packageBenchmarkData
#   benchmarks/tools/populate-benchmark-server.sh
#
# Posts each resource individually rather than as a transaction bundle: Synthea references resources
# by urn:uuid within a bundle, and rewriting those correctly is more work than a one-off load needs.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/fhir}"
DATA_DIR="${DATA_DIR:-benchmarks/core/build/benchmark-data/synthea}"
# Loaded in reference order, so a resource's targets already exist when it lands.
TYPES="${TYPES:-Organization Practitioner Patient Encounter Condition Observation}"

if [ ! -d "${DATA_DIR}" ]; then
  echo "No packaged data at ${DATA_DIR}." >&2
  echo "Run ./gradlew :benchmarks:core:packageBenchmarkData first." >&2
  exit 1
fi

if ! curl -sf "${BASE_URL}/metadata" >/dev/null 2>&1; then
  echo "No FHIR server answering at ${BASE_URL}." >&2
  exit 1
fi

total=0
for type in ${TYPES}; do
  file="${DATA_DIR}/${type}.ndjson"
  [ -f "${file}" ] || { echo "Skipping ${type}: no ${file}"; continue; }

  count=0
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    id=$(printf '%s' "${line}" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
    if [ -n "${id}" ]; then
      curl -sf -X PUT -H "Content-Type: application/fhir+json" \
        --data-binary "${line}" "${BASE_URL}/${type}/${id}" >/dev/null || true
    else
      curl -sf -X POST -H "Content-Type: application/fhir+json" \
        --data-binary "${line}" "${BASE_URL}/${type}" >/dev/null || true
    fi
    count=$((count + 1))
  done < "${file}"

  total=$((total + count))
  echo "${type}: ${count}"
done

echo "Uploaded ${total} resources to ${BASE_URL}."
