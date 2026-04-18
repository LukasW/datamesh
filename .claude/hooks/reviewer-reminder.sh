#!/usr/bin/env bash
# PostToolUse-Hook: erinnert Claude daran, den passenden Reviewer-Agenten zu nutzen,
# wenn kritische Pfade der datamesh-Plattform bearbeitet wurden. Exit 0 = nicht-blockierend.
#
# Input: JSON auf stdin mit tool_input.file_path (+ ggf. weiteren Feldern).
# Output: stderr wird bei Exit 2 an Claude als Feedback gegeben; wir nutzen Exit 0
#         und schreiben einen Hinweis auf stderr, den Claude Code anzeigt.

set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  exit 0
fi

payload=$(cat)
file_path=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null || true)

if [[ -z "$file_path" ]]; then
  exit 0
fi

reminders=()

# Hexagonal: alle Java-Dateien in den Domain-Modulen
case "$file_path" in
  */src/main/java/ch/yuno/*/*.java)
    reminders+=("hexagonal-reviewer — Java-Backend geändert, prüfe Ports/Adapters und Layer-Grenzen.")
    ;;
esac

# Auth/Security: REST-Resources, Security-Infrastruktur, Auth-Configs
case "$file_path" in
  */infrastructure/web/*Resource*.java|*/infrastructure/security/*|*keycloak*|*oidc*|*/auth/*|*application*.properties|*application*.yml)
    reminders+=("auth-security-reviewer — Auth/OIDC/Session-Pfad oder Config berührt.")
    ;;
esac

# Kafka / Messaging: Consumer/Producer, Outbox-Migrations
case "$file_path" in
  */infrastructure/messaging/*.java|*/db/migration/*outbox*|*/db/migration/*Outbox*)
    reminders+=("kafka-messaging-reviewer — Messaging/Outbox-Pfad berührt, prüfe Transactional-Outbox.")
    ;;
esac

# Data Contracts: ODC, Avro-Schemas
case "$file_path" in
  */src/main/resources/contracts/*.yaml|*/src/main/resources/contracts/*.yml|*/src/main/avro/*.avsc)
    reminders+=("data-contract-reviewer — ODC/Schema geändert, prüfe Topic-Naming und PII-Markierung.")
    ;;
esac

# Qute-Templates & htmx-Controllers
case "$file_path" in
  */src/main/resources/templates/*.html|*/src/main/resources/templates/**/*.html)
    reminders+=("qute-htmx-reviewer — Template geändert, prüfe Sprache/Escape/htmx-Patterns.")
    ;;
esac

# SQLMesh / Iceberg Data Product
case "$file_path" in
  */data-product/sqlmesh/*|*/data-product/sqlmesh/**/*|*/data-product/debezium/*|*/data-product/soda/*|infra/sqlmesh/*|infra/sqlmesh/**/*)
    reminders+=("sqlmesh-iceberg-reviewer — Data-Product-Layer berührt, prüfe Layer-Disziplin und Audits.")
    ;;
esac

# PII / ADR-009: Partner-Domain Producer, person.v1.state Consumer, Vault-Adapter
case "$file_path" in
  partner/src/main/java/*|*/infrastructure/vault/*|*/security/*Vault*|*person.v1.state*|*/contracts/person-*.yaml)
    reminders+=("pii-encryption-reviewer — PII/Vault-Pfad berührt (ADR-009), prüfe Crypto-Shredding-Invarianten.")
    ;;
esac

if (( ${#reminders[@]} > 0 )); then
  {
    echo "[reviewer-reminder] geänderte Pfade erfordern Review:"
    for r in "${reminders[@]}"; do
      echo "  - $r"
    done
  } >&2
fi

exit 0
