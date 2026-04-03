#!/usr/bin/env zsh
# build.sh – Build all Datamesh container images (Maven + custom images)
#
# This script ONLY builds. For deployment use:
#   - deploy-compose.sh     → Docker Compose
#   - infra/k8s/deploy.sh   → kind Kubernetes
set -euo pipefail

cd "$(dirname "$0")"

# ── Container runtime detection ──────────────────────────────────────────────
if command -v docker &>/dev/null; then
  CONTAINER_CMD=docker
elif command -v podman &>/dev/null; then
  CONTAINER_CMD=podman
else
  echo "ERROR: Neither docker nor podman is installed." >&2
  exit 1
fi

IMAGE_REGISTRY="${IMAGE_REGISTRY:-yuno}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
SKIP_TESTS=true

# Podman: use Docker format to support HEALTHCHECK instructions from Quarkus/Jib
if [[ "$CONTAINER_CMD" == "podman" ]]; then
  export BUILDAH_FORMAT=docker
fi

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Build all Datamesh service container images.

Options:
  -t               Run tests (default: skip)
  -h               Show this help

Environment:
  IMAGE_REGISTRY   Container image prefix (default: yuno)
  IMAGE_TAG        Image tag (default: latest)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t) SKIP_TESTS=false ;;
    -h) usage; exit 0 ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
  esac
  shift
done

echo "▶ Building  |  runtime=${CONTAINER_CMD}  |  registry=${IMAGE_REGISTRY}  |  tag=${IMAGE_TAG}  |  tests=$( [[ $SKIP_TESTS == false ]] && echo on || echo off )"

# ── Maven build (all Quarkus services) ───────────────────────────────────────
mvn clean package \
  -DskipTests="$SKIP_TESTS" \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group="$IMAGE_REGISTRY" \
  -Dquarkus.container-image.additional-tags="$IMAGE_TAG"

# ── Trino Vault UDF plugin ──────────────────────────────────────────────────
echo ""
echo "▶ Building Trino Vault UDF plugin..."
mvn -f infra/trino/vault-udf/pom.xml clean package -q

# ── Custom container images ─────────────────────────────────────────────────
echo ""
echo "▶ Building custom container images..."
$CONTAINER_CMD build -t yuno/debezium-connect:latest infra/debezium/
$CONTAINER_CMD build -t yuno/superset:local infra/superset/
$CONTAINER_CMD build -t yuno/trino:local infra/trino/
$CONTAINER_CMD build -t yuno/sqlmesh:local infra/sqlmesh/

echo ""
echo "✓ All images built successfully."
