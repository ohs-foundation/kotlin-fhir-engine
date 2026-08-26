#!/usr/bin/env bash
#
# Starts a local HAPI FHIR server for the `server` benchmark group, then waits until it answers.
#
#   benchmarks/tools/start-benchmark-server.sh
#   ./gradlew :benchmarks:core:desktopTest -Pbenchmark.groups=server \
#     -Pbenchmark.server=http://localhost:8080/fhir
#
# Needs a working `docker` CLI. These numbers include the server and the loopback network, so they
# are only comparable to another run against the same server on the same machine.
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-fhir-benchmark-server}"
IMAGE="${IMAGE:-hapiproject/hapi:latest}"
PORT="${PORT:-8080}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-300}"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is not on PATH. Install the docker CLI, or point -Pbenchmark.server at any" >&2
  echo "reachable FHIR server instead of using this script." >&2
  exit 1
fi

if [ -n "$(docker ps -aq -f "name=^${CONTAINER_NAME}$")" ]; then
  echo "Removing the previous ${CONTAINER_NAME} container so the run starts empty."
  docker rm -f "${CONTAINER_NAME}" >/dev/null
fi

echo "Starting ${IMAGE} as ${CONTAINER_NAME} on port ${PORT}."
docker run -d --name "${CONTAINER_NAME}" -p "${PORT}:8080" "${IMAGE}" >/dev/null

BASE_URL="http://localhost:${PORT}/fhir"
echo -n "Waiting for ${BASE_URL}/metadata"
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
until curl -sf "${BASE_URL}/metadata" >/dev/null 2>&1; do
  if [ "${SECONDS}" -ge "${deadline}" ]; then
    echo
    echo "Server did not answer within ${READY_TIMEOUT_SECONDS}s. Logs:" >&2
    docker logs --tail 50 "${CONTAINER_NAME}" >&2
    exit 1
  fi
  echo -n "."
  sleep 2
done

echo
echo "Ready. Run benchmarks with -Pbenchmark.server=${BASE_URL}"
