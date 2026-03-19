#!/usr/bin/env zsh
# build.sh – Build all service container images (Podman + Maven) and restart Compose
set -euo pipefail

cd "$(dirname "$0")"

SCRIPT_NAME="$(basename "$0")"
SKIP_TESTS=true
DAEMON_MODE=false
DELETE_VOLUMES=false

usage() {
  cat <<EOF
Usage: $SCRIPT_NAME [-t] [-d] [--delete-volumes] [-h]

  -t               Run tests (default: skip)
  -d               Start compose in detached mode
  --delete-volumes Delete volumes on compose down
  -h               Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t) SKIP_TESTS=false ;;
    -d) DAEMON_MODE=true ;;
    --delete-volumes) DELETE_VOLUMES=true ;;
    -h) usage; exit 0 ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
  esac
  shift
done

echo "▶ Building  |  tests=$( [[ $SKIP_TESTS == false ]] && echo on || echo off )  |  volumes=$( [[ $DELETE_VOLUMES == true ]] && echo delete || echo keep )"

mvn clean package \
  -DskipTests="$SKIP_TESTS" \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.additional-tags=latest

echo "▶ Restarting Compose…"

podman compose down $([[ "$DELETE_VOLUMES" == true ]] && echo "-v") 2>/dev/null || true
podman compose build
podman compose up $([[ "$DAEMON_MODE" == true ]] && echo "-d")

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
fi

echo "✓ Done  |  Partner→:9080  Product→:9081  Policy→:9082  Debezium→:8083"
