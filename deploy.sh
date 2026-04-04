#!/usr/bin/env zsh
# deploy-compose.sh – Build and deploy the platform via Docker Compose
set -euo pipefail

cd "$(dirname "$0")"

# ── Container runtime detection ──────────────────────────────────────────────
if command -v podman &>/dev/null; then
  CONTAINER_CMD=podman
  COMPOSE_CMD="podman compose"
elif command -v docker &>/dev/null; then
  CONTAINER_CMD=docker
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

SKIP_BUILD=false
DAEMON_MODE=false
DELETE_VOLUMES=false
CREATE_TEST_DATA=false

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Build and deploy the platform via Docker Compose.

Options:
  --skip-build     Skip Maven build (reuse existing images)
  -d               Start compose in detached mode
  --delete-volumes Delete volumes on compose down
  --test-data      Seed all services with test data after stack is healthy
                   (implies -d; requires Keycloak + all services to be up)
  -t               Run tests during build
  -h               Show this help
EOF
}

RUN_TESTS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=true ;;
    -t) RUN_TESTS="-t" ;;
    -d) DAEMON_MODE=true ;;
    --delete-volumes) DELETE_VOLUMES=true ;;
    --test-data) CREATE_TEST_DATA=true; DAEMON_MODE=true ;;
    -h) usage; exit 0 ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
  esac
  shift
done

# ── Build ────────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == false ]]; then
  ./build.sh ${RUN_TESTS}
fi

# ── Compose deploy ───────────────────────────────────────────────────────────
echo ""
echo "▶ Restarting Compose..."

${=COMPOSE_CMD} down $([[ "$DELETE_VOLUMES" == true ]] && echo "-v") 2>/dev/null || true
${=COMPOSE_CMD} build
${=COMPOSE_CMD} up $([[ "$DAEMON_MODE" == true ]] && echo "-d")

if [[ "$DAEMON_MODE" == true ]]; then
  echo ""
  echo "▶ Registering JSON Schemas in Schema Registry..."
  scripts/register-schemas.sh || echo "  ⚠ Schema registration failed (non-blocking)"

  if [[ "$CREATE_TEST_DATA" == true ]]; then
    echo ""
    echo "▶ Seeding test data..."
    scripts/seed-test-data.sh

    echo ""
    echo "▶ Restarting Debezium outbox connectors (re-snapshot for Iceberg)…"
    for conn in partner-outbox-connector product-outbox-connector policy-outbox-connector billing-outbox-connector claims-outbox-connector; do
      curl -sf -X POST "http://localhost:8083/connectors/${conn}/restart?includeTasks=true" > /dev/null 2>&1 \
        && echo "  ✓ ${conn}" || echo "  ⚠ ${conn} (may not exist yet)"
    done

    echo ""
    echo "▶ Waiting for Debezium Iceberg sinks to commit raw data…"
    for attempt in $(seq 1 60); do
      # Check Nessie directly for raw table entries
      NESSIE_TABLES=$(curl -sf "http://localhost:19120/api/v2/trees/main/entries" 2>/dev/null \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(1 for e in d.get('entries',[]) if e['type']=='ICEBERG_TABLE' and '_raw.' in '.'.join(e['name']['elements'])))" 2>/dev/null || echo "0")
      if [[ "${NESSIE_TABLES}" -ge 5 ]] 2>/dev/null; then
        echo "  ✓ Raw tables committed to Nessie (${NESSIE_TABLES} tables)"
        # Give sinks a few more seconds to flush remaining data
        sleep 10
        break
      fi
      echo "  Waiting… (${NESSIE_TABLES}/5 raw tables in Nessie, attempt $attempt/60)"
      sleep 5
    done

    echo ""
    echo "▶ Running Silver/Gold transformations (Iceberg → Trino)..."
    ${=COMPOSE_CMD} run --rm transform-init
  fi

  echo ""
  echo "╔══════════════════════════════════════════════════════════════════════════╗"
  echo "║              ✓  Deployment erfolgreich abgeschlossen                    ║"
  echo "╠══════════════════════════════════════════════════════════════════════════╣"
  echo "║  Domain Services    URL                               Port              ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  Partner Service    http://localhost:9080              :9080             ║"
  echo "║  Product Service    http://localhost:9081              :9081             ║"
  echo "║  Policy Service     http://localhost:9082              :9082             ║"
  echo "║  Claims Service     http://localhost:9083              :9083             ║"
  echo "║  Billing Service    http://localhost:9084              :9084             ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  Externe Systeme & Integration                                          ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  HR-System (COTS)   http://localhost:9085              :9085             ║"
  echo "║  HR-Integration     http://localhost:9086/q/health     :9086             ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  Infrastruktur                                                          ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  Keycloak Admin     http://localhost:8280              :8280             ║"
  echo "║  AKHQ (Kafka UI)    http://localhost:8085              :8085             ║"
  echo "║  Schema Registry    http://localhost:8081              :8081             ║"
  echo "║  Vault              http://localhost:8200              :8200             ║"
  echo "║  Prometheus         http://localhost:9090              :9090             ║"
  echo "║  Jaeger (Tracing)   http://localhost:16686             :16686            ║"
  echo "║  Grafana            http://localhost:3000              :3000             ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  Data Mesh & Analytics                                                  ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  MinIO Console      http://localhost:9001              :9001             ║"
  echo "║  Nessie Catalog     http://localhost:19120/api/v2      :19120            ║"
  echo "║  Trino              http://localhost:8086              :8086             ║"
  echo "║  Superset           http://localhost:8088              :8088             ║"
  echo "║  OpenMetadata       http://localhost:8585              :8585             ║"
  echo "╠══════════════════════════════════════════════════════════════════════════╣"
  echo "║  Zugangsdaten                                                           ║"
  echo "║  ────────────────────────────────────────────────────────────────────   ║"
  echo "║  Domain Services    admin / admin  (via Keycloak, alle Rollen)          ║"
  echo "║  Keycloak Admin     admin / admin                                       ║"
  echo "║  Grafana            admin / admin                                       ║"
  echo "║  Superset           admin / admin                                       ║"
  echo "║  OpenMetadata       admin@open-metadata.org / admin                    ║"
  echo "║  Trino              (kein Auth – anonymer Zugriff)                      ║"
  echo "║  MinIO              minioadmin / minioadmin                             ║"
  echo "║  Vault              Token: dev-root-token                               ║"
  echo "╚══════════════════════════════════════════════════════════════════════════╝"
fi
