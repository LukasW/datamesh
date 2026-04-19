#!/usr/bin/env zsh
# export-sqlmesh-lineage.sh – Export SQLMesh model DAG to OpenMetadata as lineage.
#
# Runs `export-sqlmesh-lineage.py` inside a throw-away `sqlmesh-init` container
# (has sqlmesh[trino] + all models mounted via compose). Called at the end of
# scripts/init-openmetadata.sh and safe to run standalone.
set -euo pipefail

cd "$(dirname "$0")/.."

OM_URL="${OM_URL:-http://localhost:8585}"
OM_INTERNAL_URL="${OM_INTERNAL_URL:-http://openmetadata-server:8585}"
OM_TOKEN="${OM_TOKEN:-}"

# ── Container/compose detection ───────────────────────────────────────────
if [[ -z "${COMPOSE_CMD:-}" ]]; then
  if command -v podman &>/dev/null; then
    COMPOSE_CMD="podman compose"
  elif docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
  elif command -v docker-compose &>/dev/null; then
    COMPOSE_CMD="docker-compose"
  else
    echo "  ✗ Neither podman nor docker compose is available" >&2
    exit 1
  fi
fi

# ── Authenticate if no token was injected ────────────────────────────────
if [[ -z "$OM_TOKEN" ]]; then
  PASS=$(echo -n "admin" | base64)
  OM_TOKEN=$(curl -sf -X POST "$OM_URL/api/v1/users/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"admin@open-metadata.org\",\"password\":\"$PASS\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])") \
    || { echo "  ✗ Could not obtain OpenMetadata token" >&2; exit 1; }
fi

# ── Run export inside sqlmesh-init (profile: tools) ───────────────────────
${=COMPOSE_CMD} run --rm --no-deps \
  -v "$(pwd)/scripts/export-sqlmesh-lineage.py:/tmp/export-sqlmesh-lineage.py:ro" \
  -e OM_URL="$OM_INTERNAL_URL" \
  -e OM_TOKEN="$OM_TOKEN" \
  --entrypoint python3 \
  sqlmesh-init /tmp/export-sqlmesh-lineage.py
