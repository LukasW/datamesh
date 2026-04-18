#!/usr/bin/env bash
# refresh-superset-datasets.sh – Refresh column metadata for all Superset datasets.
#
# Superset caches each dataset's column list at provisioning time. When the
# backing Iceberg/Trino tables are created *after* provisioning (e.g. by
# transform-init), the cached column list stays empty and charts render as
# "Data error — Columns missing in dataset". Re-introspecting the dataset via
# PUT /api/v1/dataset/{id}/refresh repopulates the column metadata from Trino.
set -euo pipefail

SUPERSET_URL="${SUPERSET_URL:-http://localhost:8088}"
SUPERSET_USER="${SUPERSET_USER:-admin}"
SUPERSET_PASSWORD="${SUPERSET_PASSWORD:-admin}"

echo "▶ Refreshing Superset dataset columns (${SUPERSET_URL})…"

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# ── 1. Login ────────────────────────────────────────────────────────────────
login_response="$(curl -sf -c "$COOKIE_JAR" \
  -H "Content-Type: application/json" \
  -X POST "${SUPERSET_URL}/api/v1/security/login" \
  -d "{\"username\":\"${SUPERSET_USER}\",\"password\":\"${SUPERSET_PASSWORD}\",\"provider\":\"db\",\"refresh\":true}")" || {
  echo "  ✗ Login failed — is Superset up at ${SUPERSET_URL}?" >&2
  exit 1
}

ACCESS_TOKEN="$(echo "$login_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')"
if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "  ✗ Login response did not contain access_token: ${login_response}" >&2
  exit 1
fi

# ── 2. CSRF token ───────────────────────────────────────────────────────────
csrf_response="$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "${SUPERSET_URL}/api/v1/security/csrf_token/")" || {
  echo "  ✗ Failed to fetch CSRF token" >&2
  exit 1
}
CSRF_TOKEN="$(echo "$csrf_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("result",""))')"
if [[ -z "$CSRF_TOKEN" ]]; then
  echo "  ✗ CSRF response did not contain a token: ${csrf_response}" >&2
  exit 1
fi

# ── 3. List dataset IDs ─────────────────────────────────────────────────────
datasets_response="$(curl -sf -b "$COOKIE_JAR" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "${SUPERSET_URL}/api/v1/dataset/?q=(page_size:100)")" || {
  echo "  ✗ Failed to list datasets" >&2
  exit 1
}

# Emit "id<TAB>table_name" per line so we can log both during refresh.
DATASET_LINES="$(echo "$datasets_response" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
for ds in payload.get("result", []):
    print(str(ds["id"]) + "\t" + ds.get("table_name", ""))
')"

if [[ -z "$DATASET_LINES" ]]; then
  echo "  ⊘ No datasets found — nothing to refresh."
  exit 0
fi

# ── 4. Refresh each dataset ─────────────────────────────────────────────────
REFRESHED=0
FAILED=0
while IFS=$'\t' read -r id name; do
  [[ -z "$id" ]] && continue
  if curl -sf -b "$COOKIE_JAR" \
      -H "Authorization: Bearer ${ACCESS_TOKEN}" \
      -H "X-CSRFToken: ${CSRF_TOKEN}" \
      -H "Referer: ${SUPERSET_URL}/" \
      -X PUT "${SUPERSET_URL}/api/v1/dataset/${id}/refresh" > /dev/null 2>&1; then
    echo "  ✓ [${id}] ${name}"
    ((REFRESHED++))
  else
    echo "  ✗ [${id}] ${name} — refresh failed"
    ((FAILED++))
  fi
done <<< "$DATASET_LINES"

echo ""
echo "Superset refresh complete: ${REFRESHED} refreshed, ${FAILED} failed"

if (( FAILED > 0 )); then
  exit 1
fi
