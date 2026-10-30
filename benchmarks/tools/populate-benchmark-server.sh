#!/usr/bin/env bash
#
# Uploads the packaged Synthea data to the benchmark server for the `server.download` workloads.
#
#   ./gradlew :benchmarks:core:packageBenchmarkData
#   benchmarks/tools/populate-benchmark-server.sh
#
# Implemented in scripts/populate_benchmark_server.py.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

exec python3 "${REPO_ROOT}/scripts/populate_benchmark_server.py" \
  --base-url "${BASE_URL:-http://localhost:8080/fhir}" \
  --data-dir "${DATA_DIR:-${REPO_ROOT}/benchmarks/core/build/benchmark-data/synthea}" \
  "$@"
