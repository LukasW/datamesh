# Data Mesh Implementation Plan

This plan maps the gap between the current application state and the target architecture
defined in [datamesh-spec.md](../specs/datamesh-spec.md). It covers only the **Data Mesh
infrastructure and Governance layers** — domain services (partner, product, policy, claims,
billing) are out of scope.

---

## 1. Current State vs. Target State

| Concern | Current Implementation | Target (datamesh-spec.md) |
|---|---|---|
| **Analytical Storage** | `platform-db` (PostgreSQL) | Apache Iceberg on MinIO/S3, Project Nessie catalog |
| **Data Ingestion** | `platform-consumer` (Python Kafka → PostgreSQL) | Iceberg Kafka Connector (Kafka → Iceberg/Parquet on S3) |
| **Transformations** | `dbt` (SQL on PostgreSQL) | **SQLMesh** (incremental models on Iceberg) |
| **Query Engine** | Direct SQL on `platform-db` | **Trino** (federated queries on Iceberg/Parquet) |
| **Streaming Analytics** | `spark-streaming` (Spark Structured Streaming) | Not in spec — Trino + SQLMesh cover this |
| **Scheduling** | Apache Airflow (webserver, scheduler, db, init) | SQLMesh built-in scheduler (or minimal Airflow if needed) |
| **Metadata Catalog** | DataHub (6 containers: mysql, elasticsearch, gms, frontend, actions, kafka-setup, upgrade, ingest) | **OpenMetadata** (self-service catalog, PII tags, retention) |
| **Data Lineage** | DataHub lineage | **OpenLineage & Marquez** (active lineage tracking) |
| **Data Quality** | Custom `governance` container (lint-contracts.py, schema-compat-check.sh, check-freshness.py) | **Soda Core / ODCS** (contract testing before Iceberg ingest) |
| **AI Act Compliance** | Not implemented | **Great Expectations** (automated data docs) |
| **Data Privacy** | Not implemented | **Crypto-Shredding** via HashiCorp Vault / KMS (ADR-009) |
| **BI / Dashboards** | Not implemented (custom Flask `portal` for static reports) | **Apache Superset** (Self-Service BI on Trino, Row-Level Security + Keycloak SSO) |
| **Data Portal** | Custom Flask `portal` app | OpenMetadata UI (replaces custom portal) |

---

## 2. Services to REMOVE

These services are replaced by the target architecture and should be deleted from
`docker-compose.yaml` along with their `infra/` directories.

### 2.1 Analytics Layer (replaced by Iceberg + Trino + SQLMesh)

| Service | docker-compose | infra/ directory | Reason |
|---|---|---|---|
| `platform-db` | lines ~678–703 | `infra/platform/init.sql` | Replaced by Iceberg on MinIO |
| `platform-consumer` | lines ~705–725 | `infra/platform/` (Dockerfile, consumer.py, requirements.txt) | Replaced by Iceberg Kafka Connector |
| `spark-streaming` | lines ~729–749 | `infra/spark/` (Dockerfile, streaming.py) | Replaced by Trino + SQLMesh |
| `dbt` | lines ~751–773 | `infra/dbt/` (entire directory) | Replaced by SQLMesh |

### 2.2 Scheduling (replaced by SQLMesh scheduler)

| Service | docker-compose | infra/ directory | Reason |
|---|---|---|---|
| `airflow-db` | lines ~834–853 | — | No longer needed |
| `airflow-init` | lines ~855–878 | `infra/airflow/Dockerfile` | No longer needed |
| `airflow-scheduler` | lines ~880–913 | `infra/airflow/dags/` | No longer needed |
| `airflow-webserver` | lines ~915–941 | `infra/airflow/` (entire directory) | No longer needed |

### 2.3 Metadata & Governance (replaced by OpenMetadata + OpenLineage)

| Service | docker-compose | infra/ directory | Reason |
|---|---|---|---|
| `datahub-mysql` | lines ~946–968 | — | Replaced by OpenMetadata |
| `datahub-elasticsearch` | lines ~970–991 | — | Replaced by OpenMetadata |
| `datahub-kafka-setup` | lines ~993–1006 | — | Replaced by OpenMetadata |
| `datahub-upgrade` | lines ~1008–1042 | — | Replaced by OpenMetadata |
| `datahub-gms` | lines ~1044–1093 | — | Replaced by OpenMetadata |
| `datahub-frontend` | lines ~1095–1120 | — | Replaced by OpenMetadata |
| `datahub-actions` | lines ~1122–1144 | — | Replaced by OpenMetadata |
| `datahub-ingest` | lines ~1146–1171 | `infra/datahub/` (entire directory) | Replaced by OpenMetadata |

### 2.4 Custom Governance & Portal (replaced by Soda Core + OpenMetadata UI)

| Service | docker-compose | infra/ directory | Reason |
|---|---|---|---|
| `governance` | lines ~777–799 | `infra/governance/` (entire directory) | Replaced by Soda Core |
| `portal` | lines ~803–830 | `infra/portal/` (entire directory) | Replaced by OpenMetadata UI |

### 2.5 Volumes to remove

```
platform-db-data, spark-warehouse, spark-ivy2, airflow-db-data,
datahub-mysql-data, datahub-esdata
```

### 2.6 Summary: Resource savings

Removing these **18 containers** frees approximately:
- ~9 GB RAM (Elasticsearch alone uses 1.5 GB, DataHub GMS 1.3 GB, Spark 2 GB, Airflow ~1.5 GB)
- ~6 CPU cores
- Significant disk I/O and startup time

---

## 3. Services to ADD

### Phase 1: Lakehouse Foundation

| Service | Image / Build | Purpose | Config Notes |
|---|---|---|---|
| **minio** | `minio/minio` | S3-compatible object store for Iceberg Parquet files | Port 9000 (API) + 9001 (Console). Create bucket `warehouse` on init. |
| **nessie** | `projectnessie/nessie` | Git-like catalog for Iceberg tables (branching, tagging, time-travel) | Port 19120. Backend: in-memory or RocksDB for dev. |
| **trino** | `trinodb/trino` | Distributed SQL query engine reading from Iceberg | Port 8086. Catalog config pointing to Nessie + MinIO. |
| **superset** | `apache/superset` | Self-Service BI dashboards on Trino | Port 8088. Connects to Trino via `sqlalchemy-trino`. Keycloak SSO (OIDC) + Row-Level Security for PII. |
| **iceberg-kafka-connect** | Add Iceberg Sink Connector to existing `debezium-connect` | Writes Kafka events as Iceberg tables on MinIO | Extend Debezium Dockerfile to include the Iceberg Sink Connector plugin. Reuses existing Connect worker. |

### Phase 2: Transformation Layer

| Service | Image / Build | Purpose | Config Notes |
|---|---|---|---|
| **sqlmesh** | Custom Dockerfile with `sqlmesh[trino]` | Incremental model transforms on Iceberg via Trino | Mount models from `infra/sqlmesh/models/`. Connects to Trino. Replaces dbt + Airflow scheduling. |

### Phase 3: Governance & Compliance

| Service | Image / Build | Purpose | Config Notes |
|---|---|---|---|
| **openmetadata** | `openmetadata/server` + `openmetadata/ingestion` | Unified metadata catalog, PII tagging, data discovery | Port 8585. Needs its own PostgreSQL + Elasticsearch. Replaces DataHub + custom portal. |
| **marquez** | `marquezproject/marquez` + `marquezproject/marquez-web` | OpenLineage-compatible lineage server | Port 5050 (API) + 3001 (Web). Needs its own PostgreSQL. |
| **vault** | `hashicorp/vault` | KMS for Crypto-Shredding (ADR-009) — per-entity encryption keys | Port 8200. Dev mode for local. Production: HA with Consul backend. |
| **soda-core** | Custom Dockerfile | Contract testing / data quality checks before Iceberg ingest | Runs as one-shot or scheduled via SQLMesh. Replaces custom `governance` container. |

---

## 4. Implementation Steps

### Step 1: Add Lakehouse Foundation
1. Add `minio` + init container to `docker-compose.yaml`
2. Add `nessie` to `docker-compose.yaml`
3. Add `trino` with Iceberg-Nessie-S3 catalog config under `infra/trino/`
4. Add `superset` with Trino datasource, Keycloak OIDC, and Row-Level Security config under `infra/superset/`
5. Extend `debezium-connect` Dockerfile with the Iceberg Sink Connector
6. Create Iceberg sink connector configs under `infra/debezium/` for each domain topic
7. **Verify:** Query domain events via Trino SQL on Iceberg tables, create a sample Superset dashboard

### Step 2: Migrate Transformations from dbt to SQLMesh
1. Create `infra/sqlmesh/` directory with config and model files
2. Port existing dbt models (`dim_partner`, `dim_product`, `fact_policies`, etc.) to SQLMesh SQL
3. Configure SQLMesh to connect to Trino (Iceberg catalog)
4. Add `sqlmesh` service to `docker-compose.yaml`
5. **Verify:** SQLMesh incremental runs produce correct mart tables on Iceberg

### Step 3: Replace DataHub with OpenMetadata
1. Remove all 8 DataHub containers + `infra/datahub/` directory
2. Add OpenMetadata server + ingestion + supporting DB + Elasticsearch
3. Configure ingestion connectors for Kafka topics, Iceberg tables, and ODC contracts
4. Set up PII classification tags and retention policies
5. **Verify:** All data products visible in OpenMetadata UI with correct tags

### Step 4: Add Lineage Tracking
1. Add `marquez` (API + Web) to `docker-compose.yaml`
2. Integrate OpenLineage emitters into SQLMesh (built-in support) and Iceberg connector
3. Configure OpenMetadata to display Marquez lineage
4. **Verify:** End-to-end lineage from CDC → Kafka → Iceberg → SQLMesh mart visible in Marquez

### Step 5: Add Crypto-Shredding (ADR-009)
1. Add `vault` to `docker-compose.yaml`
2. Implement per-entity AES-256 key generation in Vault (keyed by e.g. `partner/{personId}`)
3. Modify domain event publishers to encrypt PII fields before writing to Kafka
4. Implement key deletion endpoint (GDPR/nDSG right-to-erasure)
5. **Verify:** After key deletion, Parquet files on MinIO are unreadable for that entity

### Step 6: Add Data Quality with Soda Core
1. Create `infra/soda/` with Soda check definitions derived from existing ODC contracts
2. Add SodaCL checks for null rates, duplicates, freshness per topic
3. Integrate Soda Core as a quality gate in the Iceberg ingest pipeline
4. Remove old `infra/governance/` container
5. **Verify:** Invalid data blocked from Iceberg ingest, quality results visible in OpenMetadata

### Step 7: Remove Legacy Services
1. Remove `platform-db`, `platform-consumer`, `spark-streaming`, `dbt` from `docker-compose.yaml`
2. Remove `airflow-db`, `airflow-init`, `airflow-scheduler`, `airflow-webserver`
3. Remove custom `governance` and `portal` containers
4. Delete corresponding `infra/` directories: `platform/`, `spark/`, `dbt/`, `airflow/`, `governance/`, `portal/`
5. Clean up volumes: `platform-db-data`, `spark-warehouse`, `spark-ivy2`, `airflow-db-data`, `datahub-mysql-data`, `datahub-esdata`
6. Update `docs/services-overview.md` to reflect the new architecture
7. **Verify:** `podman compose up` starts cleanly with only the new stack

### Step 8: Documentation Updates
1. Update `specs/arc42.md` Section 5 (Building Block View) and Section 7 (Deployment View)
2. Update `docs/services-overview.md` with new service descriptions
3. Update `CLAUDE.md` tech stack table
4. Add ADR-009 (Crypto-Shredding) to the ADR list

---

## 5. New Target Architecture (after migration)

```
┌─────────────────────────────────────────────────────────────┐
│                    E. Visualization & BI                     │
│                      Apache Superset                         │
├─────────────────────────────────────────────────────────────┤
│                    D. Query & Transform                     │
│              Trino ← Iceberg ← SQLMesh                     │
├─────────────────────────────────────────────────────────────┤
│                    C. Analytical Storage                    │
│         MinIO/S3  ←  Apache Iceberg  ←  Nessie             │
├─────────────────────────────────────────────────────────────┤
│                    B. Transport Layer                       │
│     Kafka (Strimzi/KRaft) + Crypto-Shredding (Vault)       │
├─────────────────────────────────────────────────────────────┤
│                    A. Operational Layer                     │
│  Partner │ Product │ Policy │ Claims │ Billing (Quarkus+PG) │
├─────────────────────────────────────────────────────────────┤
│                    Governance                               │
│  OpenMetadata │ OpenLineage/Marquez │ Soda Core │ Vault     │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Risk Notes

| Risk | Mitigation |
|---|---|
| MinIO/S3 adds storage management complexity | Use S3 lifecycle policies; Nessie garbage collection for orphan files |
| Trino cold-start query latency | Trino caching + Iceberg metadata caching in Nessie |
| OpenMetadata is newer than DataHub | Actively maintained; aligns with ODC standard; good Kafka/Iceberg connectors |
| Crypto-Shredding requires all consumers to handle encrypted fields | Centralize decryption in a Kafka interceptor or Trino UDF |
| SQLMesh is less mainstream than dbt | Native Iceberg support, built-in scheduler, eliminates Airflow dependency |
| Superset adds another stateful service (metadata DB, cache) | Use existing PostgreSQL pattern (dedicated `superset-db`); Redis for caching optional in dev |
