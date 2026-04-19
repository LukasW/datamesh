#!/usr/bin/env zsh
# export-superset-lineage.sh – Push Superset dashboard → Trino-table lineage to OM.
#
# Workaround for OM 1.12.4 ↔ Superset 6.0.1 connector drift that prevents the
# native connector from extracting charts. Runs the python script inside the
# sqlmesh-init container (has sqlglot via sqlmesh[trino]).
set -euo pipefail

cd "$(dirname "$0")/.."

OM_URL="${OM_URL:-http://localhost:8585}"
OM_INTERNAL_URL="${OM_INTERNAL_URL:-http://openmetadata-server:8585}"
SUPERSET_INTERNAL_URL="${SUPERSET_INTERNAL_URL:-http://superset:8088}"
OM_TOKEN="${OM_TOKEN:-}"

if [[ -z "${COMPOSE_CMD:-}" ]]; then
  if command -v podman &>/dev/null; then
    COMPOSE_CMD="podman compose"
  elif docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
  else
    echo "  ✗ No podman/docker compose available" >&2
    exit 1
  fi
fi

if [[ -z "$OM_TOKEN" ]]; then
  PASS=$(echo -n "admin" | base64)
  OM_TOKEN=$(curl -sf -X POST "$OM_URL/api/v1/users/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"admin@open-metadata.org\",\"password\":\"$PASS\"}" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])") \
    || { echo "  ✗ Could not obtain OM token" >&2; exit 1; }
fi

${=COMPOSE_CMD} run --rm --no-deps \
  -v "$(pwd)/scripts/export-superset-lineage.py:/tmp/export-superset-lineage.py:ro" \
  -e OM_URL="$OM_INTERNAL_URL" \
  -e OM_TOKEN="$OM_TOKEN" \
  -e SUPERSET_URL="$SUPERSET_INTERNAL_URL" \
  --entrypoint python3 \
  sqlmesh-init /tmp/export-superset-lineage.py
