#!/usr/bin/env bash
# check-raw-tables.sh – Verify that all Iceberg raw tables contain data.
#
# Queries Trino via its HTTP statement API and waits until every raw table
# reports count(*) > 0, or exits non-zero on timeout. Replaces the previous
# Nessie-metadata-only check in deploy.sh, which passed even when sinks had
# auto-created empty tables without writing data (issue #4).
set -euo pipefail

TRINO_URL="${TRINO_URL:-http://localhost:8086}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-300}"
SLEEP_SECONDS="${SLEEP_SECONDS:-5}"

RAW_TABLES=(
  "partner_raw.person_events"
  "product_raw.product_events"
  "policy_raw.policy_events"
  "billing_raw.billing_events"
  "claims_raw.claims_events"
  "hr_raw.employee_events"
  "hr_raw.org_unit_events"
)

# trino_count <fully-qualified-table>
# Prints the row count on stdout, or "-1" on any error (unreachable, table
# missing, Trino error). Silent – callers format their own output.
trino_count() {
  local table="$1"
  local sql="SELECT count(*) FROM iceberg.${table}"
  python3 - "$TRINO_URL" "$sql" <<'PY' 2>/dev/null || echo "-1"
import json
import sys
import time
import urllib.error
import urllib.request

trino_url, sql = sys.argv[1], sys.argv[2]
req = urllib.request.Request(
    f"{trino_url}/v1/statement",
    data=sql.encode("utf-8"),
    headers={"X-Trino-User": "deploy-check"},
)
try:
    resp = urllib.request.urlopen(req, timeout=10)
except urllib.error.URLError:
    print(-1)
    sys.exit(0)

body = json.loads(resp.read())
if body.get("error"):
    print(-1)
    sys.exit(0)

next_uri = body.get("nextUri")
result = body.get("data")
while next_uri:
    time.sleep(0.3)
    try:
        resp = urllib.request.urlopen(next_uri, timeout=10)
    except urllib.error.URLError:
        print(-1)
        sys.exit(0)
    body = json.loads(resp.read())
    if body.get("error", {}).get("message"):
        print(-1)
        sys.exit(0)
    if body.get("data"):
        result = body["data"]
    next_uri = body.get("nextUri")

try:
    print(int(result[0][0]))
except (TypeError, ValueError, IndexError):
    print(-1)
PY
}

echo "▶ Verifying raw-table row counts via Trino (${TRINO_URL})…"

elapsed=0
while (( elapsed < MAX_WAIT_SECONDS )); do
  populated=0
  summary=""
  for table in "${RAW_TABLES[@]}"; do
    count="$(trino_count "$table")"
    summary+="${table}=${count} "
    if [[ "$count" =~ ^[0-9]+$ ]] && (( count > 0 )); then
      populated=$(( populated + 1 ))
    fi
  done

  if (( populated == ${#RAW_TABLES[@]} )); then
    echo "  ✓ Raw tables have data (${populated}/${#RAW_TABLES[@]}: ${summary% })"
    exit 0
  fi

  echo "  Waiting… (${populated}/${#RAW_TABLES[@]} raw tables populated, ${elapsed}s/${MAX_WAIT_SECONDS}s)"
  sleep "$SLEEP_SECONDS"
  elapsed=$(( elapsed + SLEEP_SECONDS ))
done

echo "  ✗ Timeout: raw tables still empty or unreachable after ${MAX_WAIT_SECONDS}s" >&2
echo "    Last seen: ${summary% }" >&2
exit 1
