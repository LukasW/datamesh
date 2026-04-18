#!/usr/bin/env bash
# reset-iceberg-sinks.sh – Idempotently reset Iceberg sinks + their consumer groups.
#
# Background: Iceberg sink connectors run under Kafka Connect consumer groups
# named `connect-iceberg-sink-<domain>`. When a sink is deleted and recreated
# (e.g. after a Nessie rebuild or a schema change), the new connector joins the
# same group and inherits the old log-end offsets (LAG=0) — no data is re-read
# and `committed to 0 table(s)` appears silently. This script automates the
# full reset: delete the connector, delete the consumer group, re-register the
# connector from its JSON config.
#
# Usage:
#   scripts/reset-iceberg-sinks.sh              # reset all 7 sinks
#   scripts/reset-iceberg-sinks.sh partner      # reset only one
#   scripts/reset-iceberg-sinks.sh partner product
#
# Env overrides:
#   CONNECT_URL       (default http://localhost:8083)
#   KAFKA_CONTAINER   (auto-detected; falls back to datamesh-kafka-1)
#   KAFKA_BOOTSTRAP   (default localhost:9092 — in-container address)
#
# Ref: arch-problem.md §6, issue #7.

set -euo pipefail

cd "$(dirname "$0")/.."

# ── Container runtime detection (same logic as deploy-compose.sh) ────────────
if command -v podman &>/dev/null; then
  CONTAINER_CMD=podman
elif command -v docker &>/dev/null; then
  CONTAINER_CMD=docker
else
  echo "ERROR: Neither podman nor docker is installed." >&2
  exit 1
fi

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"

# Auto-detect the Kafka container name (matches `datamesh_kafka_1`,
# `datamesh-kafka-1`, or a user-renamed instance). Fall back to the default
# Compose-generated name when detection produces nothing.
if [[ -z "${KAFKA_CONTAINER:-}" ]]; then
  KAFKA_CONTAINER="$(${CONTAINER_CMD} ps --format '{{.Names}}' 2>/dev/null \
    | grep -E '(^|[_-])kafka([_-]|$)' \
    | grep -v -E 'connect|ui|init|registry' \
    | head -n1 || true)"
  KAFKA_CONTAINER="${KAFKA_CONTAINER:-datamesh-kafka-1}"
fi

# ── Sink domain → config-file mapping ────────────────────────────────────────
# Keys must match the `<domain>` suffix in the connector name
# `iceberg-sink-<domain>` and consumer group `connect-iceberg-sink-<domain>`.
declare -A SINK_CONFIG=(
  [partner]="partner/data-product/debezium/iceberg-sink.json"
  [product]="product/data-product/debezium/iceberg-sink.json"
  [policy]="policy/data-product/debezium/iceberg-sink.json"
  [billing]="billing/data-product/debezium/iceberg-sink.json"
  [claims]="claims/data-product/debezium/iceberg-sink.json"
  [hr-employee]="hr-system/data-product/debezium/iceberg-sink-employee.json"
  [hr-orgunit]="hr-system/data-product/debezium/iceberg-sink-orgunit.json"
)

ALL_DOMAINS=(partner product policy billing claims hr-employee hr-orgunit)

# ── Argument parsing ─────────────────────────────────────────────────────────
if [[ $# -eq 0 ]]; then
  TARGETS=("${ALL_DOMAINS[@]}")
else
  TARGETS=("$@")
  for d in "${TARGETS[@]}"; do
    if [[ -z "${SINK_CONFIG[$d]:-}" ]]; then
      echo "ERROR: unknown domain '$d'. Known: ${ALL_DOMAINS[*]}" >&2
      exit 1
    fi
  done
fi

# ── Pre-flight: Connect reachable? ───────────────────────────────────────────
if ! curl -sf -o /dev/null "$CONNECT_URL/connectors"; then
  echo "ERROR: Kafka Connect not reachable at $CONNECT_URL" >&2
  echo "       Is the compose stack up? (check: ${CONTAINER_CMD} ps)" >&2
  exit 1
fi

# ── Pre-flight: Kafka container reachable? ───────────────────────────────────
if ! ${CONTAINER_CMD} exec "$KAFKA_CONTAINER" true 2>/dev/null; then
  echo "ERROR: Kafka container '$KAFKA_CONTAINER' not running." >&2
  echo "       Override with KAFKA_CONTAINER=<name> or start the stack." >&2
  exit 1
fi

# ── Helpers ──────────────────────────────────────────────────────────────────
delete_connector() {
  local name="$1"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$CONNECT_URL/connectors/$name" || echo 000)"
  case "$code" in
    204|200) echo "    ✓ connector deleted";;
    404)     echo "    · connector absent (nothing to delete)";;
    *)       echo "    ⚠ unexpected HTTP $code deleting connector" >&2; return 1;;
  esac
}

delete_consumer_group() {
  local group="$1"
  local output
  if ! output="$(${CONTAINER_CMD} exec "$KAFKA_CONTAINER" \
      kafka-consumer-groups \
      --bootstrap-server "$KAFKA_BOOTSTRAP" \
      --delete --group "$group" 2>&1)"; then
    if grep -qE "does not exist|GroupIdNotFound" <<<"$output"; then
      echo "    · consumer group absent (nothing to delete)"
      return 0
    fi
    echo "    ⚠ kafka-consumer-groups failed:" >&2
    echo "$output" | sed 's/^/      /' >&2
    return 1
  fi
  echo "    ✓ consumer group deleted"
}

register_connector() {
  local config_file="$1"
  local code
  code="$(curl -s -o /tmp/reset-iceberg-sinks.err -w '%{http_code}' \
    -X POST "$CONNECT_URL/connectors" \
    -H 'Content-Type: application/json' \
    --data-binary "@$config_file" || echo 000)"
  case "$code" in
    201|200) echo "    ✓ connector registered";;
    409)     echo "    ⚠ connector already exists (HTTP 409) — delete step may have failed" >&2; return 1;;
    *)       echo "    ⚠ unexpected HTTP $code registering connector" >&2;
             [[ -s /tmp/reset-iceberg-sinks.err ]] && sed 's/^/      /' /tmp/reset-iceberg-sinks.err >&2;
             return 1;;
  esac
}

# ── Main loop ────────────────────────────────────────────────────────────────
echo "▶ Resetting Iceberg sinks: ${TARGETS[*]}"
echo "  Connect: $CONNECT_URL   Kafka: $KAFKA_CONTAINER ($KAFKA_BOOTSTRAP)"
echo ""

declare -A RESULT
for domain in "${TARGETS[@]}"; do
  connector="iceberg-sink-$domain"
  group="connect-$connector"
  config="${SINK_CONFIG[$domain]}"

  echo "──  $domain  ────────────────────────────────────────────"

  if [[ ! -f "$config" ]]; then
    echo "    ✗ config file not found: $config" >&2
    RESULT[$domain]="fail"
    continue
  fi

  ok=true
  delete_connector "$connector" || ok=false
  # Give Kafka Connect a moment to release group membership before delete.
  sleep 2
  delete_consumer_group "$group" || ok=false
  register_connector "$config" || ok=false

  if $ok; then
    RESULT[$domain]="ok"
  else
    RESULT[$domain]="fail"
  fi
done

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "▶ Summary"
fail_count=0
for domain in "${TARGETS[@]}"; do
  case "${RESULT[$domain]}" in
    ok)   echo "  ✓ $domain";;
    fail) echo "  ✗ $domain"; fail_count=$(( fail_count + 1 ));;
  esac
done

if (( fail_count > 0 )); then
  echo ""
  echo "✗ $fail_count sink(s) did not reset cleanly — inspect output above." >&2
  exit 1
fi

echo ""
echo "✓ All targeted sinks reset. Sinks should now re-read topics from earliest."
echo "  Verify with: scripts/check-raw-tables.sh"
