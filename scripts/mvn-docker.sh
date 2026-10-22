#!/usr/bin/env bash
# Runs Maven for the platform service inside a pinned JDK 21 container, so the host needs only Docker.
# Usage: scripts/mvn-docker.sh [maven args...]   (default: verify = unit tests + Testcontainers ITs)
#
# The Docker socket is mounted so Testcontainers can start sibling containers (Postgres, Kafka, the
# FHIR mock); they publish ports on the Docker host, which this container reaches as
# host.docker.internal (Docker Desktop).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="maven:3.9.11-eclipse-temurin-21"
[ $# -eq 0 ] && set -- verify
exec docker run --rm \
  -v "$ROOT":/work \
  -v portal-m2-cache:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  -w /work/platform \
  "$IMAGE" mvn -B -ntp "$@"
