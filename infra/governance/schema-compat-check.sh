#!/usr/bin/env bash
# Schema Compatibility Check
# Validates every Avro schema in the project against the Schema Registry.
#
# For each .avsc file:
#   - If the subject already exists: tests backward compatibility
#   - If the subject does not exist: registers the schema
#
# Exit code: 0 = all compatible, 1 = at least one INCOMPATIBLE result.
#
# Dependencies: curl, jq
# Environment:
#   SCHEMA_REGISTRY_URL  (default: http://localhost:8081)

set -euo pipefail

SCHEMA_REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve Avro directories
if [ -d "/avro" ]; then
  AVRO_DIRS=("/avro/partner" "/avro/policy" "/avro/product")
else
  REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
  AVRO_DIRS=(
    "$REPO_ROOT/partner/src/main/resources/contracts"
    "$REPO_ROOT/policy/src/main/resources/contracts"
    "$REPO_ROOT/product/src/main/resources/contracts"
  )
fi

PASS=0
FAIL=0
TOTAL=0

separator() { printf '%s\n' "$(printf '─%.0s' {1..60})"; }

separator
echo "  Schema Compatibility Check – Registry: $SCHEMA_REGISTRY_URL"
separator

for dir in "${AVRO_DIRS[@]}"; do
  [ -d "$dir" ] || continue
  for avsc_file in "$dir"/*.avsc; do
    [ -f "$avsc_file" ] || continue
    TOTAL=$((TOTAL + 1))

    filename="$(basename "$avsc_file" .avsc)"
    subject="${filename}-value"

    # Read and escape the schema for JSON embedding
    schema_content=$(cat "$avsc_file")
    schema_json=$(jq -n --arg s "$schema_content" '{"schema": $s}')

    # Check if subject exists
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
      "$SCHEMA_REGISTRY_URL/subjects/$subject/versions/latest")

    if [ "$http_code" = "200" ]; then
      # Subject exists: test compatibility
      response=$(curl -s -X POST \
        "$SCHEMA_REGISTRY_URL/compatibility/subjects/$subject/versions/latest" \
        -H "Content-Type: application/vnd.schemaregistry.v1+json" \
        -d "$schema_json")

      is_compatible=$(echo "$response" | jq -r '.is_compatible // "false"')
      if [ "$is_compatible" = "true" ]; then
        printf "  COMPATIBLE    %s\n" "$filename"
        PASS=$((PASS + 1))
      else
        printf "  INCOMPATIBLE  %s  ← BREAKING CHANGE DETECTED\n" "$filename"
        echo "    Response: $response"
        FAIL=$((FAIL + 1))
      fi
    elif [ "$http_code" = "404" ]; then
      # Subject does not exist: register schema
      reg_response=$(curl -s -X POST \
        "$SCHEMA_REGISTRY_URL/subjects/$subject/versions" \
        -H "Content-Type: application/vnd.schemaregistry.v1+json" \
        -d "$schema_json")

      if echo "$reg_response" | jq -e '.id' > /dev/null 2>&1; then
        schema_id=$(echo "$reg_response" | jq -r '.id')
        printf "  REGISTERED    %s  (id=%s)\n" "$filename" "$schema_id"
        PASS=$((PASS + 1))
      else
        printf "  ERROR         %s  ← registration failed\n" "$filename"
        echo "    Response: $reg_response"
        FAIL=$((FAIL + 1))
      fi
    else
      printf "  ERROR         %s  ← registry returned HTTP %s\n" "$filename" "$http_code"
      FAIL=$((FAIL + 1))
    fi
  done
done

separator
if [ "$TOTAL" -eq 0 ]; then
  echo "  WARNING: No .avsc files found"
else
  echo "  Result: $PASS passed, $FAIL failed (of $TOTAL schemas)"
fi
separator

[ "$FAIL" -eq 0 ]
