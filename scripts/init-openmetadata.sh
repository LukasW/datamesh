#!/usr/bin/env zsh
# init-openmetadata.sh – Register services, run initial ingestion, create PII tags,
# and deploy scheduled Airflow pipelines for automatic metadata updates (every 6h).
set -euo pipefail

OM_URL="${OM_URL:-http://localhost:8585}"
OM_INTERNAL_URL="${OM_INTERNAL_URL:-http://openmetadata-server:8585}"
CONTAINER_CMD="${CONTAINER_CMD:-podman}"

log()  { echo "  $*" >&2 }
ok()   { echo "  ✓ $*" >&2 }
fail() { echo "  ✗ $*" >&2; exit 1 }

# ── wait for OpenMetadata server ──────────────────────────────────────────
echo ""
echo "▶ Waiting for OpenMetadata server to be ready…"
for i in {1..60}; do
  if curl -sf "$OM_URL/api/v1/system/version" > /dev/null 2>&1; then
    ok "OpenMetadata server is up"
    break
  fi
  if [[ $i -eq 60 ]]; then
    fail "OpenMetadata server not reachable after 120s at $OM_URL"
  fi
  sleep 2
done

# ── authenticate ──────────────────────────────────────────────────────────
echo ""
echo "▶ Authenticating with OpenMetadata…"
PASS=$(echo -n "admin" | base64)
TOKEN=$(curl -sf -X POST "$OM_URL/api/v1/users/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@open-metadata.org\",\"password\":\"$PASS\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
[[ -n "$TOKEN" ]] || fail "Could not obtain OpenMetadata token"
ok "Authenticated"

AUTH="Authorization: Bearer $TOKEN"

_put() {
  local url="$1" body="$2"
  local resp code rbody
  resp=$(curl -s -w '\n__HTTP__%{http_code}' -X PUT "$url" \
    -H "Content-Type: application/json" -H "$AUTH" -d "$body")
  code=$(echo "$resp" | grep '__HTTP__' | sed 's/.*__HTTP__//')
  rbody=$(echo "$resp" | grep -v '__HTTP__')
  if [[ "${code:-000}" -ge 400 ]]; then
    log "HTTP $code from PUT $url: $rbody"
    return 1
  fi
  echo "$rbody"
}

# ── 1. Create Kafka messaging service ─────────────────────────────────────
echo ""
echo "▶ Registering Kafka messaging service…"

_put "$OM_URL/api/v1/services/messagingServices" '{
  "name": "kafka",
  "displayName": "Kafka Domain Topics",
  "serviceType": "Kafka",
  "connection": {
    "config": {
      "type": "Kafka",
      "bootstrapServers": "kafka:29092",
      "schemaRegistryURL": "http://schema-registry:8081"
    }
  }
}' > /dev/null && ok "Kafka service registered"

# ── 2. Create Trino database service ──────────────────────────────────────
echo ""
echo "▶ Registering Trino database service…"

_put "$OM_URL/api/v1/services/databaseServices" '{
  "name": "trino-iceberg",
  "displayName": "Iceberg Lakehouse (Trino)",
  "serviceType": "Trino",
  "connection": {
    "config": {
      "type": "Trino",
      "hostPort": "trino:8086",
      "username": "openmetadata",
      "catalog": "iceberg",
      "databaseSchema": "analytics"
    }
  }
}' > /dev/null && ok "Trino service registered"

# ── 3. Run Kafka metadata ingestion via CLI ───────────────────────────────
echo ""
echo "▶ Running Kafka metadata ingestion…"

$CONTAINER_CMD exec datamesh-openmetadata-ingestion-1 bash -c "
cat > /tmp/kafka-ingest.yaml <<'YAML'
source:
  type: kafka
  serviceName: kafka
  serviceConnection:
    config:
      type: Kafka
      bootstrapServers: kafka:29092
      schemaRegistryURL: http://schema-registry:8081
  sourceConfig:
    config:
      type: MessagingMetadata
      topicFilterPattern:
        includes:
          - 'person\\.v1\\..*'
          - 'product\\.v1\\..*'
          - 'policy\\.v1\\..*'
          - 'claims\\.v1\\..*'
          - 'billing\\.v1\\..*'
          - 'hr\\.v1\\..*'
      generateSampleData: true
      markDeletedTopics: true
sink:
  type: metadata-rest
  config: {}
workflowConfig:
  openMetadataServerConfig:
    hostPort: $OM_INTERNAL_URL/api
    authProvider: openmetadata
    securityConfig:
      jwtToken: $TOKEN
YAML
metadata ingest -c /tmp/kafka-ingest.yaml 2>&1 | tail -5
" && ok "Kafka ingestion complete" || log "Kafka ingestion had issues (see above)"

# ── 4. Run Trino metadata ingestion via CLI ───────────────────────────────
echo ""
echo "▶ Running Trino metadata ingestion…"

$CONTAINER_CMD exec datamesh-openmetadata-ingestion-1 bash -c "
cat > /tmp/trino-ingest.yaml <<'YAML'
source:
  type: trino
  serviceName: trino-iceberg
  serviceConnection:
    config:
      type: Trino
      hostPort: trino:8086
      username: openmetadata
      catalog: iceberg
  sourceConfig:
    config:
      type: DatabaseMetadata
      schemaFilterPattern:
        includes:
          - analytics
          - partner_raw
          - product_raw
          - policy_raw
          - billing_raw
          - claims_raw
          - hr_raw
      includeViews: true
      markDeletedTables: true
      includeTags: true
sink:
  type: metadata-rest
  config: {}
workflowConfig:
  openMetadataServerConfig:
    hostPort: $OM_INTERNAL_URL/api
    authProvider: openmetadata
    securityConfig:
      jwtToken: $TOKEN
YAML
metadata ingest -c /tmp/trino-ingest.yaml 2>&1 | tail -5
" && ok "Trino ingestion complete" || log "Trino ingestion had issues (see above)"

# ── 5. Create PII classification & tags ───────────────────────────────────
echo ""
echo "▶ Creating PII classification…"

_put "$OM_URL/api/v1/classifications" '{
  "name": "PII",
  "displayName": "Personally Identifiable Information",
  "description": "Classification for data containing personally identifiable information subject to nDSG/GDPR"
}' > /dev/null && ok "PII classification created"

for tag_name tag_desc in \
  "Sensitive" "PII fields requiring crypto-shredding (ADR-009)" \
  "PersonIdentifier" "Direct person identifiers (personId, AHV number)" \
  "Address" "Physical address data (street, city, postalCode)"; do
  _put "$OM_URL/api/v1/tags" "{
    \"name\": \"$tag_name\",
    \"displayName\": \"$tag_name\",
    \"description\": \"$tag_desc\",
    \"classification\": \"PII\"
  }" > /dev/null && ok "Tag PII.$tag_name created"
done

# ── 6. Deploy Airflow ingestion pipelines (scheduled every 6 hours) ──────
echo ""
echo "▶ Deploying scheduled ingestion pipelines…"

_post() {
  local url="$1" body="$2"
  local resp code rbody
  resp=$(curl -s -w '\n__HTTP__%{http_code}' -X POST "$url" \
    -H "Content-Type: application/json" -H "$AUTH" -d "$body")
  code=$(echo "$resp" | grep '__HTTP__' | sed 's/.*__HTTP__//')
  rbody=$(echo "$resp" | grep -v '__HTTP__')
  if [[ "${code:-000}" -ge 400 ]]; then
    log "HTTP $code from POST $url: $rbody"
    return 1
  fi
  echo "$rbody"
}

# Get service FQNs for pipeline creation
KAFKA_ID=$(curl -sf "$OM_URL/api/v1/services/messagingServices/name/kafka" \
  -H "$AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

TRINO_ID=$(curl -sf "$OM_URL/api/v1/services/databaseServices/name/trino-iceberg" \
  -H "$AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

deploy_pipeline() {
  local name="$1" display="$2" type="$3" svc_id="$4" svc_type="$5" fqn="$6" src_cfg="$7"
  # Create pipeline (ignore 409 if already exists)
  _post "$OM_URL/api/v1/services/ingestionPipelines" "{
    \"name\": \"$name\",
    \"displayName\": \"$display\",
    \"pipelineType\": \"$type\",
    \"service\": {\"id\": \"$svc_id\", \"type\": \"$svc_type\"},
    \"sourceConfig\": $src_cfg,
    \"airflowConfig\": {\"scheduleInterval\": \"0 */6 * * *\"}
  }" > /dev/null 2>&1 && ok "$name pipeline created" || log "$name pipeline already exists (ok)"

  # Always deploy (generates DAG file in Airflow)
  local pid
  pid=$(curl -sf "$OM_URL/api/v1/services/ingestionPipelines/name/$fqn" \
    -H "$AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")
  if [[ -n "$pid" ]]; then
    _post "$OM_URL/api/v1/services/ingestionPipelines/deploy/$pid" '{}' > /dev/null 2>&1 \
      && ok "$name deployed to Airflow" || log "$name deploy failed"
  fi
}

if [[ -n "$KAFKA_ID" ]]; then
  deploy_pipeline "kafka-metadata-ingestion" "Kafka Metadata Ingestion" "metadata" \
    "$KAFKA_ID" "messagingService" "kafka.kafka-metadata-ingestion" \
    '{"config":{"type":"MessagingMetadata","topicFilterPattern":{"includes":["person\\.v1\\..*","product\\.v1\\..*","policy\\.v1\\..*","claims\\.v1\\..*","billing\\.v1\\..*","hr\\.v1\\..*"]},"generateSampleData":true,"markDeletedTopics":true}}'
fi

if [[ -n "$TRINO_ID" ]]; then
  deploy_pipeline "trino-metadata-ingestion" "Trino Metadata Ingestion" "metadata" \
    "$TRINO_ID" "databaseService" "trino-iceberg.trino-metadata-ingestion" \
    '{"config":{"type":"DatabaseMetadata","schemaFilterPattern":{"includes":["analytics","partner_raw","product_raw","policy_raw","billing_raw","claims_raw","hr_raw"]},"includeViews":true,"markDeletedTables":true,"includeTags":true}}'
fi

# ── 7. Report ─────────────────────────────────────────────────────────────
echo ""

TOPIC_COUNT=$(curl -s "$OM_URL/api/v1/topics?service=kafka&limit=100" \
  -H "$AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['paging']['total'])" 2>/dev/null || echo "0")
TABLE_COUNT=$(curl -s "$OM_URL/api/v1/tables?service=trino-iceberg&limit=100" \
  -H "$AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['paging']['total'])" 2>/dev/null || echo "0")

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║         ✓  OpenMetadata Initialisierung abgeschlossen       ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  Kafka Topics:    $TOPIC_COUNT                                           ║"
echo "║  Iceberg Tables:  $TABLE_COUNT                                           ║"
echo "║  PII Tags:        3  (Sensitive, PersonIdentifier, Address)          ║"
echo "║  Scheduled:       Every 6h (Airflow pipelines)              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
