#!/usr/bin/env bash
# check-silver-completeness.sh – Verify that policy Silver models expose
# both coverages and cancellations after transform-init.
#
# Guards against the failure mode from issue #15: even with raw data
# present, Silver could project only a subset of states (e.g. only
# ACTIVE policies) if an upstream sink had dropped CoverageAdded /
# PolicyCancelled events. We assert the Silver layer as a deploy-time
# contract: coverages materialize and both ACTIVE + CANCELLED policies
# are represented.
set -euo pipefail

TRINO_URL="${TRINO_URL:-http://localhost:8086}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-120}"
SLEEP_SECONDS="${SLEEP_SECONDS:-5}"

# trino_query <sql>
# Prints the first column of the first row, or "ERR" on any failure.
trino_query() {
  local sql="$1"
  python3 - "$TRINO_URL" "$sql" <<'PY' 2>/dev/null || echo "ERR"
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
    print("ERR")
    sys.exit(0)

body = json.loads(resp.read())
if body.get("error"):
    print("ERR")
    sys.exit(0)

next_uri = body.get("nextUri")
result = body.get("data")
while next_uri:
    time.sleep(0.3)
    try:
        resp = urllib.request.urlopen(next_uri, timeout=10)
    except urllib.error.URLError:
        print("ERR")
        sys.exit(0)
    body = json.loads(resp.read())
    if body.get("error", {}).get("message"):
        print("ERR")
        sys.exit(0)
    if body.get("data"):
        result = body["data"]
    next_uri = body.get("nextUri")

try:
    print(result[0][0])
except (TypeError, IndexError):
    print("ERR")
PY
}

echo "▶ Verifying Silver completeness via Trino (${TRINO_URL})…"

elapsed=0
while (( elapsed < MAX_WAIT_SECONDS )); do
  coverage_rows="$(trino_query "SELECT count(*) FROM iceberg.policy_silver.coverage")"
  policy_statuses="$(trino_query "SELECT array_join(array_agg(policy_status ORDER BY policy_status), ',') FROM (SELECT DISTINCT policy_status FROM iceberg.policy_silver.policy)")"

  coverage_ok=false
  if [[ "$coverage_rows" =~ ^[0-9]+$ ]] && (( coverage_rows > 0 )); then
    coverage_ok=true
  fi

  statuses_ok=false
  if [[ ",${policy_statuses}," == *",ACTIVE,"* ]] && [[ ",${policy_statuses}," == *",CANCELLED,"* ]]; then
    statuses_ok=true
  fi

  if $coverage_ok && $statuses_ok; then
    echo "  ✓ policy_silver.coverage=${coverage_rows} rows; policy_silver.policy statuses=[${policy_statuses}]"
    exit 0
  fi

  echo "  Waiting… (coverage=${coverage_rows}, statuses=[${policy_statuses}], ${elapsed}s/${MAX_WAIT_SECONDS}s)"
  sleep "$SLEEP_SECONDS"
  elapsed=$(( elapsed + SLEEP_SECONDS ))
done

echo "  ✗ Timeout: Silver layer incomplete after ${MAX_WAIT_SECONDS}s" >&2
echo "    policy_silver.coverage rows: ${coverage_rows}" >&2
echo "    policy_silver.policy statuses: [${policy_statuses}]" >&2
echo "    Re-check with:" >&2
echo "      curl -s -X POST ${TRINO_URL}/v1/statement -H 'X-Trino-User: debug' \\" >&2
echo "        -d \"SELECT policy_status, count(*) FROM iceberg.policy_silver.policy GROUP BY 1\"" >&2
exit 1
