#!/usr/bin/env bash
# DataHub full ingestion – Kafka schemas + dbt lineage + ODC metadata
# Run from project root. Start DataHub first: ./infra/datahub/start-datahub.sh
set -euo pipefail

DATAHUB_GMS="http://localhost:8080"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ── Preflight ──────────────────────────────────────────────────────────────

echo "Checking DataHub GMS at $DATAHUB_GMS..."
if ! curl -sf "$DATAHUB_GMS/health" > /dev/null; then
  echo "ERROR: DataHub GMS not reachable."
  echo "Start it with:  datahub docker quickstart"
  exit 1
fi
echo "DataHub is up."
echo

# ── Step 1: Kafka Schema Registry → DataHub ────────────────────────────────

echo "Step 1 – Kafka schema ingestion"
datahub ingest -c "$SCRIPT_DIR/recipe_kafka.yaml"
echo

# ── Step 2: dbt manifest → DataHub ────────────────────────────────────────

DBT_MANIFEST="$PROJECT_ROOT/infra/dbt/target/manifest.json"
if [ -f "$DBT_MANIFEST" ]; then
  echo "Step 2 – dbt lineage ingestion"
  datahub ingest -c "$SCRIPT_DIR/recipe_dbt.yaml"
else
  echo "Step 2 – dbt artifacts not found, skipping"
  echo "         Generate them first:"
  echo "           cd infra/dbt && dbt docs generate"
fi
echo

# ── Step 3: ODC contract metadata → DataHub ───────────────────────────────

echo "Step 3 – ODC custom properties ingestion"
python "$SCRIPT_DIR/ingest_odc.py"
echo

echo "Ingestion complete → http://localhost:9002"
