#!/usr/bin/env bash
#
# Stops and removes the benchmark FHIR server, so the next start has an empty database.
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-fhir-benchmark-server}"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is not on PATH; nothing to stop." >&2
  exit 0
fi

if [ -z "$(docker ps -aq -f "name=^${CONTAINER_NAME}$")" ]; then
  echo "No ${CONTAINER_NAME} container."
  exit 0
fi

docker rm -f "${CONTAINER_NAME}" >/dev/null
echo "Removed ${CONTAINER_NAME}."
