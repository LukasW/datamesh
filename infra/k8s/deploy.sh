#!/usr/bin/env zsh
# deploy.sh – Deploy the Datamesh platform to a local kind cluster
#
# All HTTP services are accessible via *.localhost (port 80).
# No /etc/hosts needed – *.localhost resolves to 127.0.0.1.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLUSTER_NAME="datamesh"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-yuno}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
SKIP_BUILD=false
DELETE_CLUSTER=false

# Pinned NGINX Ingress Controller version for reproducible deployments
INGRESS_NGINX_VERSION="v1.12.1"

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Deploy the Datamesh platform to a local kind Kubernetes cluster.

Options:
  --skip-build       Skip build (reuse existing images)
  --delete           Delete existing cluster and recreate from scratch
  -h, --help         Show this help

Environment:
  IMAGE_REGISTRY     Container image prefix (default: yuno)
  IMAGE_TAG          Image tag to deploy (default: latest)

Prerequisites:
  - kind    https://kind.sigs.k8s.io/
  - kubectl
  - Docker or Podman
  - Maven (unless --skip-build)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=true ;;
    --delete) DELETE_CLUSTER=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
  shift
done

# ── Helper ───────────────────────────────────────────────────────────────────
elapsed() { echo "$(( SECONDS - ${1:-0} ))s"; }
step()    { echo ""; echo "▶ $1"; }

# ── Prerequisites check ─────────────────────────────────────────────────────
for cmd in kind kubectl; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: '$cmd' is not installed." >&2
    exit 1
  fi
done

# Container runtime detection
if command -v docker &>/dev/null; then
  CONTAINER_CMD=docker
elif command -v podman &>/dev/null; then
  CONTAINER_CMD=podman
  export KIND_EXPERIMENTAL_PROVIDER=podman
else
  echo "ERROR: Neither docker nor podman is installed." >&2
  exit 1
fi

echo "▶ Container runtime: ${CONTAINER_CMD}"
echo "▶ Image registry:    ${IMAGE_REGISTRY}"
echo "▶ Image tag:         ${IMAGE_TAG}"

# ── Build phase (delegates to build.sh) ──────────────────────────────────────
if [[ "$SKIP_BUILD" == false ]]; then
  step "Building all images via build.sh..."
  "$PROJECT_ROOT/build.sh"
fi

# ── Cluster management ───────────────────────────────────────────────────────
if [[ "$DELETE_CLUSTER" == true ]]; then
  step "Deleting existing kind cluster '${CLUSTER_NAME}'..."
  kind delete cluster --name "$CLUSTER_NAME" 2>/dev/null || true
fi

if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "▶ Kind cluster '${CLUSTER_NAME}' already exists, reusing."
else
  step "Creating kind cluster '${CLUSTER_NAME}'..."
  kind create cluster --config "$SCRIPT_DIR/kind-config.yaml" --name "$CLUSTER_NAME"
fi

kubectl config use-context "kind-${CLUSTER_NAME}"

# ── Fix cgroup PID limit for Podman (kind node needs >2048 for ~35 Java pods)
if [[ "$CONTAINER_CMD" == "podman" ]]; then
  step "Raising cgroup PID limit on kind node..."
  podman update --pids-limit 8192 "${CLUSTER_NAME}-control-plane" 2>/dev/null \
    && echo "  ✓ pids.max set to 8192" \
    || echo "  ⚠ Could not update PID limit (non-fatal)"
fi

# ── Fix kube-proxy mode (K8s ≥1.33 ships without iptables binary) ───────────
step "Ensuring kube-proxy uses nftables mode..."
CURRENT_MODE=$(kubectl get configmap -n kube-system kube-proxy -o jsonpath='{.data.config\.conf}' 2>/dev/null | sed -n 's/.*mode:[[:space:]]*\([a-zA-Z]*\).*/\1/p' || echo "unknown")
CURRENT_MODE="${CURRENT_MODE:-unknown}"
if [[ "$CURRENT_MODE" != "nftables" ]]; then
  kubectl get configmap -n kube-system kube-proxy -o json \
    | python3 -c "
import json, sys, re
cm = json.load(sys.stdin)
cm['data']['config.conf'] = re.sub(r'mode:\s*\w+', 'mode: nftables', cm['data']['config.conf'])
json.dump(cm, sys.stdout)
" | kubectl apply -f - 2>/dev/null
  kubectl rollout restart daemonset -n kube-system kube-proxy 2>/dev/null
  kubectl rollout status daemonset -n kube-system kube-proxy --timeout=60s 2>/dev/null
  echo "  ✓ kube-proxy switched to nftables"
else
  echo "  ✓ kube-proxy already in nftables mode"
fi

# ── Install NGINX Ingress Controller ─────────────────────────────────────────
step "Installing NGINX Ingress Controller ${INGRESS_NGINX_VERSION}..."
kubectl apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-${INGRESS_NGINX_VERSION}/deploy/static/provider/kind/deploy.yaml"

echo "  Waiting for ingress controller to be ready..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s 2>/dev/null || echo "  ⚠ Ingress controller not ready yet (may need more time)"

# ── Load images into kind ────────────────────────────────────────────────────
step "Loading container images into kind cluster..."

DOMAIN_IMAGES=(
  "${IMAGE_REGISTRY}/partner-service:${IMAGE_TAG}"
  "${IMAGE_REGISTRY}/product-service:${IMAGE_TAG}"
  "${IMAGE_REGISTRY}/policy-service:${IMAGE_TAG}"
  "${IMAGE_REGISTRY}/claims-service:${IMAGE_TAG}"
  "${IMAGE_REGISTRY}/billing-service:${IMAGE_TAG}"
  "${IMAGE_REGISTRY}/hr-system:${IMAGE_TAG}"
  "${IMAGE_REGISTRY}/hr-integration:${IMAGE_TAG}"
)

INFRA_IMAGES=(
  "yuno/debezium-connect:latest"
  "yuno/superset:local"
  "yuno/trino:local"
  "yuno/sqlmesh:local"
)

load_image() {
  local img="$1"
  if [[ "$CONTAINER_CMD" == "podman" ]]; then
    # Podman prefixes images with 'localhost/' – kind can't find them.
    # Re-tag without prefix, then use podman save + kind load image-archive.
    local podman_img="localhost/$img"
    if ! podman image exists "$podman_img" 2>/dev/null; then
      echo "  ⚠ $img not found locally (skipping)"
      return
    fi
    podman tag "$podman_img" "docker.io/$img" 2>/dev/null
    local tmpfile
    tmpfile=$(mktemp /tmp/kind-img-XXXXXX.tar)
    podman save "docker.io/$img" -o "$tmpfile" 2>/dev/null \
      && kind load image-archive "$tmpfile" --name "$CLUSTER_NAME" 2>/dev/null \
      && echo "  ✓ $img" \
      || echo "  ⚠ Failed to load $img"
    rm -f "$tmpfile"
  else
    kind load docker-image "$img" --name "$CLUSTER_NAME" 2>/dev/null \
      && echo "  ✓ $img" \
      || echo "  ⚠ Failed to load $img (may not exist locally)"
  fi
}

for img in "${DOMAIN_IMAGES[@]}" "${INFRA_IMAGES[@]}"; do
  load_image "$img"
done

# ── Create namespace and ConfigMaps ──────────────────────────────────────────
step "Creating namespace and ConfigMaps..."
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"

create_cm() {
  local name="$1"; shift
  kubectl -n datamesh create configmap "$name" "$@" --dry-run=client -o yaml | kubectl apply -f -
}

# Observability
create_cm prometheus-config \
  --from-file=prometheus.yml="$PROJECT_ROOT/infra/prometheus/prometheus.yml"

create_cm grafana-datasources \
  --from-file=datasources.yml="$PROJECT_ROOT/infra/grafana/provisioning/datasources/prometheus.yml"

# Security
create_cm keycloak-realm \
  --from-file=yuno-realm.json="$PROJECT_ROOT/infra/keycloak/yuno-realm.json"

# Lakehouse
create_cm trino-config \
  --from-file=config.properties="$PROJECT_ROOT/infra/trino/etc/config.properties" \
  --from-file=jvm.config="$PROJECT_ROOT/infra/trino/etc/jvm.config" \
  --from-file=node.properties="$PROJECT_ROOT/infra/trino/etc/node.properties" \
  --from-file=log.properties="$PROJECT_ROOT/infra/trino/etc/log.properties"

create_cm trino-catalog \
  --from-file=iceberg.properties="$PROJECT_ROOT/infra/trino/catalog/iceberg.properties"

# Debezium CDC connectors
create_cm debezium-connectors \
  --from-file=partner-outbox-connector.json="$PROJECT_ROOT/infra/debezium/partner-outbox-connector.json" \
  --from-file=product-outbox-connector.json="$PROJECT_ROOT/infra/debezium/product-outbox-connector.json" \
  --from-file=policy-outbox-connector.json="$PROJECT_ROOT/infra/debezium/policy-outbox-connector.json" \
  --from-file=billing-outbox-connector.json="$PROJECT_ROOT/infra/debezium/billing-outbox-connector.json" \
  --from-file=claims-outbox-connector.json="$PROJECT_ROOT/infra/debezium/claims-outbox-connector.json"

# Iceberg sink connectors
create_cm iceberg-sinks \
  --from-file=iceberg-sink-partner.json="$PROJECT_ROOT/infra/debezium/iceberg-sink-partner.json" \
  --from-file=iceberg-sink-product.json="$PROJECT_ROOT/infra/debezium/iceberg-sink-product.json" \
  --from-file=iceberg-sink-policy.json="$PROJECT_ROOT/infra/debezium/iceberg-sink-policy.json" \
  --from-file=iceberg-sink-billing.json="$PROJECT_ROOT/infra/debezium/iceberg-sink-billing.json" \
  --from-file=iceberg-sink-claims.json="$PROJECT_ROOT/infra/debezium/iceberg-sink-claims.json" \
  --from-file=iceberg-sink-hr.json="$PROJECT_ROOT/infra/debezium/iceberg-sink-hr.json"

# Governance (optional files)
if [[ -f "$PROJECT_ROOT/infra/openmetadata/init-airflow-db.sql" ]]; then
  create_cm openmetadata-db-init \
    --from-file=init-airflow-db.sql="$PROJECT_ROOT/infra/openmetadata/init-airflow-db.sql"
fi

if [[ -f "$PROJECT_ROOT/infra/superset/superset_config.py" ]]; then
  create_cm superset-config \
    --from-file=superset_config.py="$PROJECT_ROOT/infra/superset/superset_config.py"
  create_cm superset-init \
    --from-file=superset-init.sh="$PROJECT_ROOT/infra/superset/superset-init.sh"
fi

# JSON Schemas for Schema Registry init job
SCHEMA_ARGS=()
for svc in partner product policy claims billing; do
  schema_dir="$PROJECT_ROOT/$svc/src/main/resources/contracts/schemas"
  if [[ -d "$schema_dir" ]]; then
    for f in "$schema_dir"/*.schema.json; do
      [[ -f "$f" ]] && SCHEMA_ARGS+=(--from-file="$(basename "$f")=$f")
    done
  fi
done
if [[ ${#SCHEMA_ARGS[@]} -gt 0 ]]; then
  create_cm json-schemas "${SCHEMA_ARGS[@]}"
fi

# ── Delete completed init jobs (for idempotent re-deploys) ───────────────────
for job in vault-init minio-init kafka-init debezium-init iceberg-init seed-data sqlmesh-init schema-registry-init openmetadata-init; do
  kubectl -n datamesh delete job "$job" --ignore-not-found 2>/dev/null
done

# ── Deploy infrastructure (Kustomize) ───────────────────────────────────────
step "Applying infrastructure manifests (Kustomize)..."
cd "$SCRIPT_DIR"
kubectl apply -k .

# ── Deploy domain services ──────────────────────────────────────────────────
step "Applying domain service manifests..."
for svc in partner product policy claims billing hr-system hr-integration; do
  kubectl apply -f "$PROJECT_ROOT/$svc/k8s/deployment.yaml"
  echo "  ✓ $svc"
done

# ── Apply Ingress (after NGINX controller webhook is ready) ──────────────────
step "Applying Ingress manifests..."
echo "  Waiting for NGINX admission webhook..."
for i in {1..30}; do
  if kubectl apply -f "$SCRIPT_DIR/ingress.yaml" 2>/dev/null; then
    echo "  ✓ Ingress resources applied"
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "  ⚠ Ingress apply failed after 30 attempts – apply manually:"
    echo "    kubectl apply -f $SCRIPT_DIR/ingress.yaml"
  fi
  sleep 5
done

# ── Wait for core infrastructure ─────────────────────────────────────────────
step "Waiting for core infrastructure to be ready..."

echo "  Databases..."
for db in partner-db product-db policy-db claims-db billing-db hr-db nessie-db; do
  kubectl -n datamesh rollout status statefulset/$db --timeout=120s 2>/dev/null || true
done

echo "  Kafka..."
kubectl -n datamesh rollout status statefulset/kafka --timeout=180s 2>/dev/null || true

echo "  Keycloak..."
kubectl -n datamesh rollout status deployment/keycloak --timeout=180s 2>/dev/null || true

echo "  Vault..."
kubectl -n datamesh rollout status deployment/vault --timeout=60s 2>/dev/null || true

# ── Wait for domain services ─────────────────────────────────────────────────
step "Waiting for domain services..."
for svc in partner-service product-service policy-service claims-service billing-service hr-system hr-integration; do
  kubectl -n datamesh rollout status deployment/$svc --timeout=180s 2>/dev/null || \
    echo "  ⚠ $svc not ready yet"
done

# ── Seed test data ─────────────────────────────────────────────────────────
step "Seeding test data via REST APIs..."
kubectl apply -f "$SCRIPT_DIR/seed-data-job.yaml"
echo "  Seed job started in background. Check: kubectl -n datamesh logs job/seed-data -f"

# ── Init jobs status ─────────────────────────────────────────────────────────
step "Init jobs running in background..."
echo "  Check status: kubectl -n datamesh get jobs"

# ── Summary ──────────────────────────────────────────────────────────────────
cat <<'SUMMARY'

╔═══════════════════════════════════════════════════════════════════════╗
║  Datamesh Platform – kind Cluster bereitgestellt (NGINX Ingress)    ║
╠═══════════════════════════════════════════════════════════════════════╣
║                                                                     ║
║  Domain Services                                                    ║
║  ─────────────────────────────────────────────────────────────────  ║
║  Partner Service         http://partner.localhost                    ║
║  Product Service         http://product.localhost                    ║
║  Policy Service          http://policy.localhost                     ║
║  Claims Service          http://claims.localhost                     ║
║  Billing Service         http://billing.localhost                    ║
║                                                                     ║
║  Externe Systeme & Integration                                      ║
║  ─────────────────────────────────────────────────────────────────  ║
║  HR-System (COTS)        http://hr.localhost                        ║
║  HR-Integration          http://hr-integration.localhost             ║
║                                                                     ║
║  Data Mesh & Governance                                             ║
║  ─────────────────────────────────────────────────────────────────  ║
║  OpenMetadata            http://openmetadata.localhost               ║
║  Apache Superset         http://superset.localhost                   ║
║  Trino                   http://trino.localhost                      ║
║  Marquez (Lineage)       http://marquez.localhost                    ║
║  MinIO Console           http://minio.localhost                      ║
║  Nessie Catalog          http://nessie.localhost                     ║
║  Vault                   http://vault.localhost                      ║
║                                                                     ║
║  Infrastruktur                                                      ║
║  ─────────────────────────────────────────────────────────────────  ║
║  Keycloak Admin          http://keycloak.localhost                   ║
║  AKHQ (Kafka UI)         http://akhq.localhost                      ║
║  Schema Registry         http://schema-registry.localhost            ║
║  Debezium Connect        http://debezium.localhost/connectors        ║
║  Prometheus              http://prometheus.localhost                  ║
║  Jaeger (Tracing)        http://jaeger.localhost                     ║
║  Grafana                 http://grafana.localhost                     ║
║                                                                     ║
║  NodePort (Non-HTTP)                                                ║
║  ─────────────────────────────────────────────────────────────────  ║
║  Kafka                   localhost:9092                              ║
║  gRPC (Premium Calc.)    localhost:9181                              ║
║  OTLP gRPC (Tracing)    localhost:4317                              ║
║                                                                     ║
╠═══════════════════════════════════════════════════════════════════════╣
║  Zugangsdaten                                                       ║
║  ─────────────────────────────────────────────────────────────────  ║
║  Domain Services    admin / admin  (via Keycloak, alle Rollen)      ║
║  Keycloak Admin     admin / admin                                   ║
║  OpenMetadata       admin@open-metadata.org / admin                 ║
║  Superset           admin / admin                                   ║
║  Grafana            admin / admin                                   ║
║  MinIO              minioadmin / minioadmin                          ║
║  Vault              Token: dev-root-token                           ║
║  Trino              User: trino  (keine Authentifizierung)          ║
╠═══════════════════════════════════════════════════════════════════════╣
║  kubectl -n datamesh get pods                                       ║
║  kubectl -n datamesh get jobs                                       ║
║  kubectl -n datamesh logs -f deployment/<service>                   ║
╚═══════════════════════════════════════════════════════════════════════╝
SUMMARY

echo "Total deployment time: $(elapsed 0)"
