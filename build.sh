#!/usr/bin/env zsh
# build.sh – Build all service container images (Podman/Docker + Maven) and restart Compose
set -euo pipefail

cd "$(dirname "$0")"

# ── container runtime detection ────────────────────────────────────────────
if command -v podman &>/dev/null; then
  CONTAINER_CMD=podman
  COMPOSE_CMD="podman compose"
elif command -v docker &>/dev/null; then
  CONTAINER_CMD=docker
  # Prefer the newer "docker compose" plugin; fall back to docker-compose
  if docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
  elif command -v docker-compose &>/dev/null; then
    COMPOSE_CMD="docker-compose"
  else
    echo "ERROR: docker found but neither 'docker compose' plugin nor 'docker-compose' is available." >&2
    exit 1
  fi
else
  echo "ERROR: Neither podman nor docker is installed." >&2
  exit 1
fi

SCRIPT_NAME="$(basename "$0")"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-css}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
SKIP_TESTS=true
DAEMON_MODE=false
DELETE_VOLUMES=false
CREATE_TEST_DATA=false

usage() {
  cat <<EOF
Usage: $SCRIPT_NAME [-t] [-d] [--delete-volumes] [--test-data] [-h]

  -t               Run tests (default: skip)
  -d               Start compose in detached mode
  --delete-volumes Delete volumes on compose down
  --test-data      Seed all services with test data after stack is healthy
                   (implies -d; requires Keycloak + all five services to be up)
  -h               Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t) SKIP_TESTS=false ;;
    -d) DAEMON_MODE=true ;;
    --delete-volumes) DELETE_VOLUMES=true ;;
    --test-data) CREATE_TEST_DATA=true; DAEMON_MODE=true ;;
    -h) usage; exit 0 ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
  esac
  shift
done

echo "▶ Building  |  runtime=${CONTAINER_CMD}  |  registry=${IMAGE_REGISTRY}  |  tag=${IMAGE_TAG}  |  tests=$( [[ $SKIP_TESTS == false ]] && echo on || echo off )  |  volumes=$( [[ $DELETE_VOLUMES == true ]] && echo delete || echo keep )"

mvn clean package \
  -DskipTests="$SKIP_TESTS" \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group="$IMAGE_REGISTRY" \
  -Dquarkus.container-image.additional-tags="$IMAGE_TAG"

echo "▶ Restarting Compose…"

${=COMPOSE_CMD} down $([[ "$DELETE_VOLUMES" == true ]] && echo "-v") 2>/dev/null || true
${=COMPOSE_CMD} build
${=COMPOSE_CMD} up $([[ "$DAEMON_MODE" == true ]] && echo "-d")

if [[ "$DAEMON_MODE" == true ]]; then
  echo "▶ Waiting for Debezium Connect to be ready…"
  for i in {1..30}; do
    if curl -sf http://localhost:8083/connectors > /dev/null 2>&1; then
      break
    fi
    sleep 2
  done

  echo "▶ Registering Debezium connectors…"
  register_connector() {
    local file="$1"
    local name
    name=$(python3 -c "import sys,json; print(json.load(open('$file'))['name'])")
    local config
    config=$(python3 -c "import sys,json; print(json.dumps(json.load(open('$file'))['config']))")
    if curl -sf -X PUT "http://localhost:8083/connectors/${name}/config" \
        -H "Content-Type: application/json" \
        -d "$config" > /dev/null; then
      echo "  ✓ ${name} registered"
    else
      echo "  ✗ ${name} registration failed"
    fi
  }

  register_connector infra/debezium/partner-outbox-connector.json
  register_connector infra/debezium/product-outbox-connector.json
  register_connector infra/debezium/policy-outbox-connector.json
  register_connector infra/debezium/billing-outbox-connector.json
  register_connector infra/debezium/claims-outbox-connector.json

  if [[ "$CREATE_TEST_DATA" == true ]]; then
    echo ""
    echo "▶ Seeding test data…"
    scripts/seed-test-data.sh
  fi

  echo ""
  echo "╔══════════════════════════════════════════════════════════════════════╗"
  echo "║            ✓  Deployment erfolgreich abgeschlossen                  ║"
  echo "╠══════════════════════════════════════════════════════════════════════╣"
  echo "║  Service           URL                              Port            ║"
  echo "║  ──────────────────────────────────────────────────────────────     ║"
  echo "║  Partner Service   http://localhost:9080             :9080          ║"
  echo "║  Product Service   http://localhost:9081             :9081          ║"
  echo "║  Policy Service    http://localhost:9082             :9082          ║"
  echo "║  Claims Service    http://localhost:9083             :9083          ║"
  echo "║  Billing Service   http://localhost:9084             :9084          ║"
  echo "║  ──────────────────────────────────────────────────────────────     ║"
  echo "║  AKHQ (Kafka UI)   http://localhost:8085             :8085          ║"
  echo "║  Keycloak          http://localhost:8180             :8180          ║"
  echo "║  Debezium Connect  http://localhost:8083/connectors  :8083          ║"
  echo "║  Prometheus        http://localhost:9090             :9090          ║"
  echo "║  Grafana           http://localhost:3000             :3000          ║"
  echo "╚══════════════════════════════════════════════════════════════════════╝"
fi
