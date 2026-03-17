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
podman compose build --no-cache
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
  curl -sf -X POST http://localhost:8083/connectors \
    -H "Content-Type: application/json" \
    -d @infra/debezium/partner-outbox-connector.json \
    && echo "  ✓ partner-outbox-connector registered" \
    || echo "  ⚠ partner-outbox-connector already exists or registration failed"
fi

echo "✓ Done  |  Partner→:9080  Product→:9081  Policy→:9082  Debezium→:8083"
