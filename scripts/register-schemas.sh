#!/usr/bin/env bash
# register-schemas.sh – Register all JSON Schema files in Confluent Schema Registry
# Uses TopicNameStrategy: subject = "{topic}-value"
set -euo pipefail

SCHEMA_REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081}"

echo "▶ Registering JSON Schemas in Schema Registry (${SCHEMA_REGISTRY_URL})…"

SERVICES=(partner product policy claims billing)
BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REGISTERED=0
FAILED=0

for svc in "${SERVICES[@]}"; do
  SCHEMA_DIR="${BASE_DIR}/${svc}/src/main/resources/contracts/schemas"
  if [[ ! -d "$SCHEMA_DIR" ]]; then
    echo "  ⊘ No schemas directory for ${svc}"
    continue
  fi

  for schema_file in "${SCHEMA_DIR}"/*.schema.json; do
    [[ -f "$schema_file" ]] || continue

    # Derive subject from filename: person.v1.created.schema.json → person.v1.created-value
    filename="$(basename "$schema_file")"
    topic="${filename%.schema.json}"
    subject="${topic}-value"

    # Read schema content and escape for JSON embedding
    schema_content="$(cat "$schema_file")"

    # Register via Schema Registry REST API
    payload=$(python3 -c "
import json, sys
schema = json.load(open('${schema_file}'))
print(json.dumps({
    'schemaType': 'JSON',
    'schema': json.dumps(schema)
}))
")

    if curl -sf -X POST "${SCHEMA_REGISTRY_URL}/subjects/${subject}/versions" \
        -H "Content-Type: application/vnd.schemaregistry.v1+json" \
        -d "$payload" > /dev/null 2>&1; then
      echo "  ✓ ${subject} registered"
      ((REGISTERED++))
    else
      echo "  ✗ ${subject} registration failed"
      ((FAILED++))
    fi
  done
done

echo ""
echo "Schema registration complete: ${REGISTERED} registered, ${FAILED} failed"
