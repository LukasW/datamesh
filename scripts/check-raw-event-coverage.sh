#!/usr/bin/env bash
# check-raw-event-coverage.sh – Verify every expected event type is present
# in the Iceberg raw tables.
#
# check-raw-tables.sh only asserts count(*) > 0 per table, which is green
# even when an Iceberg sink silently commits a single topic and drops
# others (see issues #14 / #15). This script closes that gap: for each
# raw table, it queries the eventtype column and asserts that every
# expected event type has at least one row. A missing event type means
# a sink is broken or a producer regressed, which would otherwise only
# surface as empty Silver/Gold tables downstream.
set -euo pipefail

TRINO_URL="${TRINO_URL:-http://localhost:8086}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-300}"
SLEEP_SECONDS="${SLEEP_SECONDS:-5}"

# Expected event types per raw table. Each entry lists event types that
# (a) are emitted by scripts/seed-test-data.sh, and
# (b) are consumed by a Silver SQLMesh model in
#     {domain}/data-product/sqlmesh/silver/*.sql.
# Missing one means a downstream Silver projection will be empty even
# though check-raw-tables.sh shows count(*) > 0 (= the silent failure
# mode observed in issues #14 / #15). The HR domain is excluded because
# its COTS-style producer uses a distinct event-type convention not
# aligned with the seeded pipeline checks here.
EXPECTED_EVENTS=(
  "policy_raw.policy_events:PolicyIssued,PolicyCancelled,CoverageAdded"
  "claims_raw.claims_events:ClaimOpened,ClaimSettled"
  "product_raw.product_events:ProductState,ProductDefined"
  "partner_raw.person_events:PersonState"
  "billing_raw.billing_events:InvoiceCreated,PaymentReceived,DunningInitiated,PayoutTriggered"
)

# trino_count_by_event <fully-qualified-table> <event-type>
# Prints the row count on stdout, or "-1" on any error.
trino_count_by_event() {
  local table="$1"
  local event="$2"
  local sql="SELECT count(*) FROM iceberg.${table} WHERE eventtype = '${event}'"
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

echo "▶ Verifying raw event-type coverage via Trino (${TRINO_URL})…"

elapsed=0
while (( elapsed < MAX_WAIT_SECONDS )); do
  missing=()
  summary=""
  for entry in "${EXPECTED_EVENTS[@]}"; do
    table="${entry%%:*}"
    events_csv="${entry#*:}"
    IFS=',' read -r -a events <<< "$events_csv"
    for event in "${events[@]}"; do
      count="$(trino_count_by_event "$table" "$event")"
      summary+="${table}/${event}=${count} "
      if ! [[ "$count" =~ ^[0-9]+$ ]] || (( count <= 0 )); then
        missing+=("${table}/${event}")
      fi
    done
  done

  if (( ${#missing[@]} == 0 )); then
    echo "  ✓ All expected event types present (${summary% })"
    exit 0
  fi

  echo "  Waiting… ($(( ${#missing[@]} )) event types still missing, ${elapsed}s/${MAX_WAIT_SECONDS}s)"
  sleep "$SLEEP_SECONDS"
  elapsed=$(( elapsed + SLEEP_SECONDS ))
done

echo "  ✗ Timeout: expected event types still missing after ${MAX_WAIT_SECONDS}s" >&2
echo "    Missing: ${missing[*]}" >&2
echo "    Last seen: ${summary% }" >&2
exit 1
