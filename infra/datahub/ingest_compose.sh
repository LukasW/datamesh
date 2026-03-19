#!/usr/bin/env bash
# DataHub ingestion script for Docker Compose.
# Runs after datahub-gms is healthy.
set -euo pipefail

GMS_URL="${DATAHUB_GMS_URL:-http://datahub-gms:8080}"
SR_URL="${SCHEMA_REGISTRY_URL:-http://schema-registry:8081}"

echo "Waiting for DataHub GMS at ${GMS_URL}..."
until curl -sf "${GMS_URL}/health" > /dev/null 2>&1; do
  sleep 5
done
echo "DataHub GMS is up."
echo

echo "Waiting for Schema Registry at ${SR_URL}..."
until curl -sf "${SR_URL}/subjects" > /dev/null 2>&1; do
  sleep 3
done
echo "Schema Registry is up."
echo

echo "Step 0 – Pre-register Avro schemas from contracts"
python3 - <<PYEOF
import json, pathlib, urllib.request, urllib.error, sys

SR_URL = "${SR_URL}"
contracts_root = pathlib.Path("/contracts")

ok = 0
for avsc in sorted(contracts_root.rglob("*.avsc")):
    topic = avsc.stem          # e.g. person.v1.created
    subject = f"{topic}-value"
    payload = json.dumps({"schema": avsc.read_text()}).encode()
    req = urllib.request.Request(
        f"{SR_URL}/subjects/{subject}/versions",
        data=payload,
        headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            result = json.loads(resp.read())
            print(f"  ✓ {topic} (id={result.get('id')})")
            ok += 1
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        err = json.loads(body) if body.startswith("{") else body
        # 409 = already exists (idempotent)
        if e.code == 409 or (isinstance(err, dict) and err.get("error_code") == 409):
            print(f"  = {topic} (already registered)")
            ok += 1
        else:
            print(f"  ✗ {topic}: {e.code} {body}", file=sys.stderr)

print(f"\n{ok} schemas ready.")
PYEOF
echo

echo "Step 1 – Kafka schema ingestion"
datahub ingest -c /app/recipe_kafka.yaml
echo

echo "Step 2 – ODC metadata ingestion"
DATAHUB_GMS_URL="${GMS_URL}" CONTRACTS_ROOT=/contracts python /app/ingest_odc.py
echo

echo "Ingestion complete → http://localhost:9002"
