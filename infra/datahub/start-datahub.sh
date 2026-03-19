#!/usr/bin/env bash
# Start DataHub alongside the datamesh stack.
# Uses port-adjusted compose to avoid conflicts with datamesh services.
#
# Usage:
#   ./infra/datahub/start-datahub.sh          # start DataHub
#   ./infra/datahub/start-datahub.sh --stop   # stop DataHub
#
# After start, run ingestion:
#   ./infra/datahub/ingest.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.datahub.yml"

COMPOSE_CMD="${COMPOSE_CMD:-podman compose}"

if [[ "${1:-}" == "--stop" ]]; then
  echo "Stopping DataHub..."
  $COMPOSE_CMD -f "$COMPOSE_FILE" down
  exit 0
fi

echo "Starting DataHub (this may take a few minutes on first run)..."
echo "  GMS       → http://localhost:8080"
echo "  Frontend  → http://localhost:9002"
echo ""

$COMPOSE_CMD -f "$COMPOSE_FILE" up -d

echo ""
echo "Waiting for DataHub GMS to be healthy..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/health > /dev/null 2>&1; then
    echo "DataHub is up → http://localhost:9002"
    echo ""
    echo "Run ingestion:"
    echo "  ./infra/datahub/ingest.sh"
    exit 0
  fi
  sleep 5
  echo -n "."
done

echo ""
echo "DataHub GMS did not become healthy within 5 minutes."
echo "Check logs: $COMPOSE_CMD -f $COMPOSE_FILE logs datahub-gms"
exit 1
